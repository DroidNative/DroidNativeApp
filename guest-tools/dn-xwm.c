/*
 * dn-xwm: starts rootless Xwayland and is its X11 window manager.
 *
 * Every mapped X toplevel becomes a toplevel of the DroidNative compositor
 * (its own Android window), override-redirect windows (menus, tooltips)
 * become popups of the active toplevel. Windows and their wl_surfaces are
 * matched through the serial Xwayland sends as a WL_SURFACE_SERIAL client
 * message (and passes to the compositor via xwayland_shell_v1); dn_xwm_v1 tells
 * the compositor what each serial is (dn-xwm-v1.xml). Toplevels sit at the
 * root origin and take the size of their Android window, except fixed-size
 * ones (minimum = maximum size), which keep theirs, and fullscreen ones
 * (_NET_WM_STATE_FULLSCREEN), which get the X screen's size: SDL draws for
 * that size without asking. The compositor zooms both to fit.
 *
 * Usage: dn-xwm [display]     default :0; exits when Xwayland does.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

#include <wayland-client.h>
#include <xcb/composite.h>
#include <xcb/xcb.h>

#include "dn-xwm-v1-client-protocol.h"

/* State */

struct win {
    xcb_window_t id;
    int override_redirect;
    int mapped;
    int managed;                // announced to the compositor
    int toplevel;               // mapped through MapRequest
    uint64_t serial;            // 0: no surface yet
    int16_t x, y;
    uint16_t width, height;
    int32_t want_w, want_h;     // size of the Android window, 0 unknown
    int delete_window, take_focus;
    int fixed;                  // WM_NORMAL_HINTS minimum = maximum
    int fullscreen;
    struct win *next;
};

static xcb_connection_t *xc;
static xcb_window_t root;
static struct wl_display *wd;
static struct dn_xwm_v1 *xwm;
static struct win *windows;
static pid_t xwayland_pid;

enum {
    WL_SURFACE_SERIAL, WM_PROTOCOLS, WM_DELETE_WINDOW, WM_TAKE_FOCUS, WM_STATE,
    WM_CLASS_A, NET_WM_NAME, UTF8_STRING, NET_WM_PID, NET_ACTIVE_WINDOW,
    NET_SUPPORTING_WM_CHECK, NET_SUPPORTED, NET_WM_STATE, NET_WM_STATE_FULLSCREEN,
    ATOM_COUNT
};
static const char *atom_names[ATOM_COUNT] = {
    "WL_SURFACE_SERIAL", "WM_PROTOCOLS", "WM_DELETE_WINDOW", "WM_TAKE_FOCUS",
    "WM_STATE", "WM_CLASS", "_NET_WM_NAME", "UTF8_STRING", "_NET_WM_PID",
    "_NET_ACTIVE_WINDOW", "_NET_SUPPORTING_WM_CHECK", "_NET_SUPPORTED",
    "_NET_WM_STATE", "_NET_WM_STATE_FULLSCREEN",
};
static xcb_atom_t atoms[ATOM_COUNT];

static void die(const char *msg)
{
    fprintf(stderr, "dn-xwm: %s\n", msg);
    if (xwayland_pid > 0)
        kill(xwayland_pid, SIGTERM);
    exit(1);
}

static struct win *find(xcb_window_t id)
{
    for (struct win *w = windows; w; w = w->next)
        if (w->id == id)
            return w;
    return NULL;
}

static struct win *add(xcb_window_t id)
{
    struct win *w = find(id);
    if (w)
        return w;
    w = calloc(1, sizeof(*w));
    if (!w)
        die("out of memory");
    w->id = id;
    w->next = windows;
    windows = w;
    return w;
}

static void drop(xcb_window_t id)
{
    for (struct win **p = &windows; *p; p = &(*p)->next) {
        if ((*p)->id == id) {
            struct win *w = *p;
            *p = w->next;
            free(w);
            return;
        }
    }
}

/* Properties */

static xcb_get_property_reply_t *get_prop(xcb_window_t w, xcb_atom_t prop, xcb_atom_t type,
                                          uint32_t len)
{
    xcb_get_property_cookie_t c = xcb_get_property(xc, 0, w, prop, type, 0, len);
    return xcb_get_property_reply(xc, c, NULL);
}

static void send_title(struct win *w)
{
    xcb_get_property_reply_t *r = get_prop(w->id, atoms[NET_WM_NAME], atoms[UTF8_STRING], 256);
    if (!r || xcb_get_property_value_length(r) == 0) {
        free(r);
        r = get_prop(w->id, XCB_ATOM_WM_NAME, XCB_GET_PROPERTY_TYPE_ANY, 256);
    }
    if (r) {
        int len = xcb_get_property_value_length(r);
        char *t = strndup(xcb_get_property_value(r), len);
        if (t) {
            dn_xwm_v1_set_title(xwm, w->id, t);
            free(t);
        }
        free(r);
    }
}

static void send_class(struct win *w)
{
    // WM_CLASS: "instance\0class\0"; the class names the application
    xcb_get_property_reply_t *r = get_prop(w->id, XCB_ATOM_WM_CLASS, XCB_ATOM_STRING, 256);
    if (!r)
        return;
    int len = xcb_get_property_value_length(r);
    const char *v = xcb_get_property_value(r);
    const char *inst_end = memchr(v, '\0', len);
    if (inst_end && inst_end + 1 < v + len) {
        char *cls = strndup(inst_end + 1, v + len - inst_end - 1);
        if (cls) {
            dn_xwm_v1_set_class(xwm, w->id, cls);
            free(cls);
        }
    }
    free(r);
}

static void send_pid(struct win *w)
{
    xcb_get_property_reply_t *r = get_prop(w->id, atoms[NET_WM_PID], XCB_ATOM_CARDINAL, 1);
    if (r && xcb_get_property_value_length(r) >= 4)
        dn_xwm_v1_set_pid(xwm, w->id, *(int32_t *)xcb_get_property_value(r));
    free(r);
}

static void read_protocols(struct win *w)
{
    w->delete_window = w->take_focus = 0;
    xcb_get_property_reply_t *r = get_prop(w->id, atoms[WM_PROTOCOLS], XCB_ATOM_ATOM, 32);
    if (!r)
        return;
    xcb_atom_t *a = xcb_get_property_value(r);
    int n = xcb_get_property_value_length(r) / 4;
    for (int i = 0; i < n; i++) {
        if (a[i] == atoms[WM_DELETE_WINDOW])
            w->delete_window = 1;
        if (a[i] == atoms[WM_TAKE_FOCUS])
            w->take_focus = 1;
    }
    free(r);
}

static void read_hints(struct win *w)
{
    // WM_SIZE_HINTS: flags, 4 obsolete, min w/h, max w/h, ...
    enum { P_MIN_SIZE = 1 << 4, P_MAX_SIZE = 1 << 5 };
    w->fixed = 0;
    xcb_get_property_reply_t *r = get_prop(w->id, XCB_ATOM_WM_NORMAL_HINTS,
                                           XCB_ATOM_WM_SIZE_HINTS, 18);
    if (r && xcb_get_property_value_length(r) >= 9 * 4) {
        int32_t *h = xcb_get_property_value(r);
        w->fixed = (h[0] & P_MIN_SIZE) && (h[0] & P_MAX_SIZE) &&
                   h[5] > 0 && h[6] > 0 && h[5] == h[7] && h[6] == h[8];
    }
    free(r);
}

static void read_fullscreen(struct win *w)
{
    w->fullscreen = 0;
    xcb_get_property_reply_t *r = get_prop(w->id, atoms[NET_WM_STATE], XCB_ATOM_ATOM, 32);
    if (!r)
        return;
    xcb_atom_t *a = xcb_get_property_value(r);
    int n = xcb_get_property_value_length(r) / 4;
    for (int i = 0; i < n; i++)
        if (a[i] == atoms[NET_WM_STATE_FULLSCREEN])
            w->fullscreen = 1;
    free(r);
}

static void write_fullscreen(struct win *w)
{
    xcb_change_property(xc, XCB_PROP_MODE_REPLACE, w->id, atoms[NET_WM_STATE], XCB_ATOM_ATOM,
                        32, w->fullscreen ? 1 : 0, &atoms[NET_WM_STATE_FULLSCREEN]);
}

static void read_serial(struct win *w)
{
    xcb_get_property_reply_t *r = get_prop(w->id, atoms[WL_SURFACE_SERIAL], XCB_ATOM_CARDINAL, 2);
    if (r && xcb_get_property_value_length(r) >= 8) {
        uint32_t *v = xcb_get_property_value(r);
        w->serial = ((uint64_t)v[1] << 32) | v[0];
    }
    free(r);
}

static void set_wm_state(xcb_window_t id, uint32_t state)
{
    uint32_t data[2] = { state, XCB_NONE };
    xcb_change_property(xc, XCB_PROP_MODE_REPLACE, id, atoms[WM_STATE], atoms[WM_STATE], 32, 2, data);
}

static void client_message(xcb_window_t id, xcb_atom_t type, uint32_t d0, uint32_t d1)
{
    xcb_client_message_event_t ev = { 0 };
    ev.response_type = XCB_CLIENT_MESSAGE;
    ev.format = 32;
    ev.window = id;
    ev.type = type;
    ev.data.data32[0] = d0;
    ev.data.data32[1] = d1;
    xcb_send_event(xc, 0, id, XCB_EVENT_MASK_NO_EVENT, (const char *)&ev);
}

/* Managing */

static void maybe_manage(struct win *w)
{
    if (w->managed || !w->mapped || !w->serial)
        return;
    uint32_t lo = (uint32_t)w->serial, hi = (uint32_t)(w->serial >> 32);
    if (w->override_redirect) {
        dn_xwm_v1_manage_popup(xwm, w->id, lo, hi, w->x, w->y, w->width, w->height);
    } else {
        send_title(w);
        send_class(w);
        send_pid(w);
        dn_xwm_v1_manage_toplevel(xwm, w->id, lo, hi, w->width, w->height);
    }
    w->managed = 1;
    wl_display_flush(wd);
}

static void unmanage(struct win *w)
{
    if (w->managed) {
        dn_xwm_v1_unmanage(xwm, w->id);
        wl_display_flush(wd);
    }
    w->managed = 0;
    w->mapped = 0;
    w->serial = 0;     // a new mapping gets a new surface
}

// Toplevels fill their Android window, at the root origin; fixed-size
// windows get width x height (their own or requested size)
static void place(struct win *w, uint16_t width, uint16_t height)
{
    if (w->fullscreen) {
        // The screen follows the compositor's output, so ask each time
        xcb_get_geometry_reply_t *g = xcb_get_geometry_reply(xc, xcb_get_geometry(xc, root), NULL);
        if (g) {
            width = g->width;
            height = g->height;
            free(g);
        }
    } else if (w->want_w > 0 && w->want_h > 0 && !w->fixed) {
        width = w->want_w;
        height = w->want_h;
    }
    uint32_t v[4] = { 0, 0, width ? width : 1, height ? height : 1 };
    xcb_configure_window(xc, w->id, XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y |
                         XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT, v);
    w->x = w->y = 0;
    w->width = v[2];
    w->height = v[3];
}

/* Compositor events */

static void on_configure(void *data, struct dn_xwm_v1 *x, uint32_t window,
                         int32_t width, int32_t height, uint32_t activated)
{
    (void)data; (void)x;
    struct win *w = find(window);
    if (!w)
        return;
    if (width > 0 && height > 0) {
        w->want_w = width;
        w->want_h = height;
        place(w, width, height);
    }
    if (activated) {
        uint32_t above = XCB_STACK_MODE_ABOVE;
        xcb_configure_window(xc, w->id, XCB_CONFIG_WINDOW_STACK_MODE, &above);
        xcb_set_input_focus(xc, XCB_INPUT_FOCUS_POINTER_ROOT, w->id, XCB_CURRENT_TIME);
        if (w->take_focus)
            client_message(w->id, atoms[WM_PROTOCOLS], atoms[WM_TAKE_FOCUS], XCB_CURRENT_TIME);
        xcb_change_property(xc, XCB_PROP_MODE_REPLACE, root, atoms[NET_ACTIVE_WINDOW],
                            XCB_ATOM_WINDOW, 32, 1, &w->id);
    }
    xcb_flush(xc);
}

static void on_close(void *data, struct dn_xwm_v1 *x, uint32_t window)
{
    (void)data; (void)x;
    struct win *w = find(window);
    if (!w)
        return;
    if (w->delete_window)
        client_message(w->id, atoms[WM_PROTOCOLS], atoms[WM_DELETE_WINDOW], XCB_CURRENT_TIME);
    else
        xcb_kill_client(xc, w->id);
    xcb_flush(xc);
}

static void on_kill(void *data, struct dn_xwm_v1 *x, uint32_t window)
{
    (void)data; (void)x;
    if (!find(window))
        return;
    // Closes the client's X connection; Xlib programs exit on that
    xcb_kill_client(xc, window);
    xcb_flush(xc);
}

static const struct dn_xwm_v1_listener xwm_listener = {
    .configure = on_configure,
    .close = on_close,
    .kill = on_kill,
};

static void registry_global(void *data, struct wl_registry *reg, uint32_t name,
                            const char *iface, uint32_t version)
{
    (void)data; (void)version;
    if (strcmp(iface, dn_xwm_v1_interface.name) == 0) {
        xwm = wl_registry_bind(reg, name, &dn_xwm_v1_interface, 1);
        dn_xwm_v1_add_listener(xwm, &xwm_listener, NULL);
    }
}

static void registry_remove(void *data, struct wl_registry *reg, uint32_t name)
{
    (void)data; (void)reg; (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global = registry_global,
    .global_remove = registry_remove,
};

/* X events */

static void handle_x_event(xcb_generic_event_t *ev)
{
    switch (ev->response_type & ~0x80) {
    case XCB_CREATE_NOTIFY: {
        xcb_create_notify_event_t *e = (void *)ev;
        if (e->parent != root)
            break;
        struct win *w = add(e->window);
        w->override_redirect = e->override_redirect;
        w->x = e->x; w->y = e->y; w->width = e->width; w->height = e->height;
        uint32_t mask = XCB_EVENT_MASK_PROPERTY_CHANGE;
        xcb_change_window_attributes(xc, e->window, XCB_CW_EVENT_MASK, &mask);
        break;
    }
    case XCB_MAP_REQUEST: {
        xcb_map_request_event_t *e = (void *)ev;
        struct win *w = add(e->window);
        w->toplevel = 1;
        read_protocols(w);
        read_hints(w);
        read_fullscreen(w);
        place(w, w->width, w->height);
        set_wm_state(e->window, 1 /* NormalState */);
        xcb_map_window(xc, e->window);
        break;
    }
    case XCB_MAP_NOTIFY: {
        xcb_map_notify_event_t *e = (void *)ev;
        struct win *w = find(e->window);
        if (!w)
            break;
        w->mapped = 1;
        w->override_redirect = e->override_redirect;
        if (!w->serial)
            read_serial(w);
        maybe_manage(w);
        break;
    }
    case XCB_UNMAP_NOTIFY: {
        xcb_unmap_notify_event_t *e = (void *)ev;
        struct win *w = find(e->window);
        if (!w)
            break;
        unmanage(w);
        if (w->toplevel)
            set_wm_state(e->window, 0 /* WithdrawnState */);
        break;
    }
    case XCB_DESTROY_NOTIFY: {
        xcb_destroy_notify_event_t *e = (void *)ev;
        struct win *w = find(e->window);
        if (w) {
            unmanage(w);
            drop(e->window);
        }
        break;
    }
    case XCB_CONFIGURE_REQUEST: {
        xcb_configure_request_event_t *e = (void *)ev;
        struct win *w = find(e->window);
        if (w && w->toplevel && w->mapped) {
            // A managed toplevel keeps the size of its Android window
            // (fixed-size ones may change their own size)
            place(w, (e->value_mask & XCB_CONFIG_WINDOW_WIDTH) ? e->width : w->width,
                  (e->value_mask & XCB_CONFIG_WINDOW_HEIGHT) ? e->height : w->height);
            break;
        }
        uint32_t v[7];
        int n = 0;
        uint16_t mask = e->value_mask & (XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y |
                                         XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT |
                                         XCB_CONFIG_WINDOW_BORDER_WIDTH);
        if (mask & XCB_CONFIG_WINDOW_X) v[n++] = (uint32_t)(int32_t)e->x;
        if (mask & XCB_CONFIG_WINDOW_Y) v[n++] = (uint32_t)(int32_t)e->y;
        if (mask & XCB_CONFIG_WINDOW_WIDTH) v[n++] = e->width;
        if (mask & XCB_CONFIG_WINDOW_HEIGHT) v[n++] = e->height;
        if (mask & XCB_CONFIG_WINDOW_BORDER_WIDTH) v[n++] = 0;
        xcb_configure_window(xc, e->window, mask, v);
        if (w) {
            if (mask & XCB_CONFIG_WINDOW_WIDTH) w->width = e->width;
            if (mask & XCB_CONFIG_WINDOW_HEIGHT) w->height = e->height;
        }
        break;
    }
    case XCB_CONFIGURE_NOTIFY: {
        xcb_configure_notify_event_t *e = (void *)ev;
        struct win *w = find(e->window);
        if (!w)
            break;
        w->x = e->x; w->y = e->y; w->width = e->width; w->height = e->height;
        if (w->managed && w->override_redirect) {
            dn_xwm_v1_move_popup(xwm, w->id, e->x, e->y, e->width, e->height);
            wl_display_flush(wd);
        }
        break;
    }
    case XCB_CLIENT_MESSAGE: {
        // Xwayland names a window's surface with this message to the WM
        xcb_client_message_event_t *e = (void *)ev;
        if (e->type == atoms[NET_WM_STATE] && e->format == 32) {
            // data: action (0 remove, 1 add, 2 toggle), two properties
            struct win *w = find(e->window);
            uint32_t *d = e->data.data32;
            if (!w || (d[1] != atoms[NET_WM_STATE_FULLSCREEN] &&
                       d[2] != atoms[NET_WM_STATE_FULLSCREEN]))
                break;
            w->fullscreen = d[0] == 2 ? !w->fullscreen : d[0] == 1;
            write_fullscreen(w);
            if (w->toplevel)
                place(w, w->width, w->height);
            xcb_flush(xc);
            break;
        }
        if (e->type != atoms[WL_SURFACE_SERIAL] || e->format != 32)
            break;
        struct win *w = add(e->window);
        w->serial = ((uint64_t)e->data.data32[1] << 32) | e->data.data32[0];
        maybe_manage(w);
        break;
    }
    case XCB_PROPERTY_NOTIFY: {
        xcb_property_notify_event_t *e = (void *)ev;
        struct win *w = find(e->window);
        if (!w)
            break;
        if (e->atom == atoms[WL_SURFACE_SERIAL]) {
            read_serial(w);
            maybe_manage(w);
        } else if (!w->managed || w->override_redirect) {
            break;
        } else if (e->atom == XCB_ATOM_WM_NAME || e->atom == atoms[NET_WM_NAME]) {
            send_title(w);
        } else if (e->atom == XCB_ATOM_WM_CLASS) {
            send_class(w);
        } else if (e->atom == atoms[WM_PROTOCOLS]) {
            read_protocols(w);
        } else if (e->atom == XCB_ATOM_WM_NORMAL_HINTS) {
            int was = w->fixed;
            read_hints(w);
            if (was != w->fixed)
                place(w, w->width, w->height);
            xcb_flush(xc);
        }
        wl_display_flush(wd);
        break;
    }
    default:
        break;
    }
}

/* Start-up */

static int start_xwayland(const char *display, int wm_fd)
{
    int dpy_pipe[2];
    if (pipe(dpy_pipe) < 0)
        die("pipe");
    xwayland_pid = fork();
    if (xwayland_pid < 0)
        die("fork");
    if (xwayland_pid == 0) {
        char wm[16], dfd[16];
        snprintf(wm, sizeof(wm), "%d", wm_fd);
        snprintf(dfd, sizeof(dfd), "%d", dpy_pipe[1]);
        close(dpy_pipe[0]);
        // Only the socket file in the guest's /tmp: the abstract one would
        // be shared with every Android app (and an X server on a PC)
        execlp("Xwayland", "Xwayland", display, "-rootless", "-wm", wm,
               "-displayfd", dfd, "-nolisten", "tcp", "-nolisten", "local",
               "-noreset", (char *)NULL);
        _exit(127);
    }
    close(dpy_pipe[1]);
    char buf[16] = { 0 };
    ssize_t n = read(dpy_pipe[0], buf, sizeof(buf) - 1);   // written once ready
    close(dpy_pipe[0]);
    return n > 0 ? 0 : -1;
}

static void setup_wm(void)
{
    const xcb_setup_t *setup = xcb_get_setup(xc);
    root = xcb_setup_roots_iterator(setup).data->root;

    xcb_intern_atom_cookie_t ck[ATOM_COUNT];
    for (int i = 0; i < ATOM_COUNT; i++)
        ck[i] = xcb_intern_atom(xc, 0, strlen(atom_names[i]), atom_names[i]);
    for (int i = 0; i < ATOM_COUNT; i++) {
        xcb_intern_atom_reply_t *r = xcb_intern_atom_reply(xc, ck[i], NULL);
        atoms[i] = r ? r->atom : XCB_NONE;
        free(r);
    }

    uint32_t mask = XCB_EVENT_MASK_SUBSTRUCTURE_REDIRECT | XCB_EVENT_MASK_SUBSTRUCTURE_NOTIFY |
                    XCB_EVENT_MASK_PROPERTY_CHANGE;
    xcb_void_cookie_t c = xcb_change_window_attributes_checked(xc, root, XCB_CW_EVENT_MASK, &mask);
    xcb_generic_error_t *err = xcb_request_check(xc, c);
    if (err)
        die("another window manager is running");
    // Composite must be negotiated before use; then manual redirection of
    // the root's children is what makes rootless Xwayland create surfaces
    xcb_composite_query_version_reply_t *cv = xcb_composite_query_version_reply(
        xc, xcb_composite_query_version(xc, 0, 4), NULL);
    if (!cv)
        die("Xwayland lacks the Composite extension");
    free(cv);
    xcb_void_cookie_t rc = xcb_composite_redirect_subwindows_checked(
        xc, root, XCB_COMPOSITE_REDIRECT_MANUAL);
    err = xcb_request_check(xc, rc);
    if (err)
        die("composite redirection refused");

    // EWMH: name ourselves through a check window
    xcb_window_t check = xcb_generate_id(xc);
    xcb_create_window(xc, XCB_COPY_FROM_PARENT, check, root, 0, 0, 1, 1, 0,
                      XCB_WINDOW_CLASS_INPUT_OUTPUT, XCB_COPY_FROM_PARENT, 0, NULL);
    xcb_change_property(xc, XCB_PROP_MODE_REPLACE, check, atoms[NET_SUPPORTING_WM_CHECK],
                        XCB_ATOM_WINDOW, 32, 1, &check);
    xcb_change_property(xc, XCB_PROP_MODE_REPLACE, check, atoms[NET_WM_NAME],
                        atoms[UTF8_STRING], 8, 6, "dn-xwm");
    xcb_change_property(xc, XCB_PROP_MODE_REPLACE, root, atoms[NET_SUPPORTING_WM_CHECK],
                        XCB_ATOM_WINDOW, 32, 1, &check);
    xcb_atom_t supported[] = { atoms[NET_ACTIVE_WINDOW], atoms[NET_WM_NAME], atoms[NET_WM_PID],
                               atoms[NET_WM_STATE], atoms[NET_WM_STATE_FULLSCREEN] };
    xcb_change_property(xc, XCB_PROP_MODE_REPLACE, root, atoms[NET_SUPPORTED],
                        XCB_ATOM_ATOM, 32, 5, supported);
    xcb_flush(xc);
}

int main(int argc, char **argv)
{
    const char *display = argc > 1 ? argv[1] : ":0";
    signal(SIGPIPE, SIG_IGN);

    wd = wl_display_connect(NULL);
    if (!wd)
        die("cannot connect to the Wayland compositor");
    struct wl_registry *reg = wl_display_get_registry(wd);
    wl_registry_add_listener(reg, &registry_listener, NULL);
    wl_display_roundtrip(wd);
    if (!xwm)
        die("compositor lacks dn_xwm_v1");

    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) < 0)
        die("socketpair");
    // Xwayland's end must survive exec
    int fl = fcntl(sv[1], F_GETFD);
    fcntl(sv[1], F_SETFD, fl & ~FD_CLOEXEC);
    if (start_xwayland(display, sv[1]) < 0)
        die("Xwayland did not start");
    close(sv[1]);

    xc = xcb_connect_to_fd(sv[0], NULL);
    if (xcb_connection_has_error(xc))
        die("cannot talk to Xwayland");
    setup_wm();
    fprintf(stderr, "dn-xwm: managing %s\n", display);

    struct pollfd fds[2] = {
        { xcb_get_file_descriptor(xc), POLLIN, 0 },
        { wl_display_get_fd(wd), POLLIN, 0 },
    };
    for (;;) {
        xcb_generic_event_t *ev;
        while ((ev = xcb_poll_for_event(xc))) {
            handle_x_event(ev);
            free(ev);
        }
        if (xcb_connection_has_error(xc))
            break;
        xcb_flush(xc);
        while (wl_display_prepare_read(wd) != 0)
            wl_display_dispatch_pending(wd);
        wl_display_flush(wd);
        if (poll(fds, 2, -1) < 0 && errno != EINTR) {
            wl_display_cancel_read(wd);
            break;
        }
        if (fds[1].revents & POLLIN) {
            if (wl_display_read_events(wd) < 0)
                break;
        } else {
            wl_display_cancel_read(wd);
        }
        if (wl_display_dispatch_pending(wd) < 0)
            break;
        if (fds[1].revents & (POLLERR | POLLHUP))
            break;
    }
    fprintf(stderr, "dn-xwm: Xwayland or the compositor went away\n");
    kill(xwayland_pid, SIGTERM);
    waitpid(xwayland_pid, NULL, 0);
    return 0;
}
