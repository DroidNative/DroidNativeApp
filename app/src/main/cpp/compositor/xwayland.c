/*
 * Rootless Xwayland. Xwayland gives every X window a wl_surface and names
 * it with a serial (xwayland_shell_v1); dn-xwm, the X window manager in the
 * guest, tells us over dn_xwm_v1 which serial is a toplevel or a popup.
 * Such a surface gets an internal dnc_xdg_surface without protocol
 * resources (x11 set), so rendering, input and host windows work as for
 * xdg-shell clients. Configure and close go back to dn-xwm.
 *
 * All X toplevels sit at the root origin; popups (override-redirect
 * windows: menus, tooltips) are attached to the active X toplevel with
 * their root position as offset.
 */
#include <stdlib.h>
#include <string.h>

#include "dnc_private.h"
#include "xwayland-shell-v1-server-protocol.h"
#include "dn-xwm-v1-server-protocol.h"

struct xwl_surface {
    struct wl_resource *resource;           // xwayland_surface_v1
    struct dnc_server *server;
    struct dnc_surface *surface;            // NULL once destroyed
    uint64_t serial;
    bool has_serial;
    struct wl_list link;                    // dnc_server.xwl_surfaces
    struct wl_listener surface_destroy;
};

struct dnc_xwl_window {
    struct dnc_server *server;
    uint32_t window;                        // X11 id
    uint64_t serial;
    bool popup;
    int32_t x, y, width, height;            // root coordinates
    char *title, *cls;
    int32_t pid;
    struct xwl_surface *xs;                 // attached surface, NULL before
    struct dnc_xdg_surface *xdg;            // internal role, NULL before
    struct dnc_popup *popup_data;
    struct wl_list link;                    // dnc_server.xwl_windows
};

int32_t dnc_xwayland_pid(const struct dnc_xwl_window *win)
{
    return win ? win->pid : 0;
}

static struct dnc_xwl_window *window_by_id(struct dnc_server *srv, uint32_t id)
{
    struct dnc_xwl_window *w;
    wl_list_for_each(w, &srv->xwl_windows, link) {
        if (w->window == id)
            return w;
    }
    return NULL;
}

/* Attaching windows to surfaces */

static void detach(struct dnc_xwl_window *win);

static void attach_toplevel(struct dnc_xwl_window *win, struct dnc_xdg_surface *xdg)
{
    struct dnc_toplevel *tl = dnc_toplevel_create(xdg);
    if (!tl)
        return;
    tl->title = win->title ? strdup(win->title) : NULL;
    tl->app_id = win->cls ? strdup(win->cls) : NULL;
    // Announce now if Xwayland committed content before we knew the role;
    // otherwise the next commit does (dnc_xdg_surface_commit)
    if (xdg->surface->mapped)
        dnc_xdg_surface_commit(xdg);
}

static void attach_popup(struct dnc_xwl_window *win, struct dnc_xdg_surface *xdg)
{
    struct dnc_server *srv = win->server;
    struct dnc_xwl_window *parent = srv->xwl_active;
    if (!parent || !parent->xdg || !parent->xdg->toplevel)
        return;
    struct dnc_popup *popup = calloc(1, sizeof(*popup));
    if (!popup)
        return;
    popup->xdg = xdg;
    popup->parent = parent->xdg;
    popup->x = win->x - parent->x;
    popup->y = win->y - parent->y;
    popup->width = win->width;
    popup->height = win->height;
    wl_list_insert(parent->xdg->toplevel->popups.prev, &popup->link);
    xdg->popup = popup;
    xdg->surface->role = DNC_ROLE_XDG_POPUP;
    win->popup_data = popup;
    dnc_toplevel_schedule_repaint(parent->xdg->toplevel);
}

// Gives the window its role once both the manage request and the serial
// of its surface are known (they race)
static void try_attach(struct dnc_xwl_window *win)
{
    struct dnc_server *srv = win->server;
    if (win->xdg)
        return;
    struct xwl_surface *xs;
    wl_list_for_each(xs, &srv->xwl_surfaces, link) {
        if (!xs->has_serial || xs->serial != win->serial || !xs->surface)
            continue;
        if (xs->surface->role != DNC_ROLE_NONE || xs->surface->role_data)
            return;
        struct dnc_xdg_surface *xdg = calloc(1, sizeof(*xdg));
        if (!xdg)
            return;
        xdg->server = srv;
        xdg->surface = xs->surface;
        xdg->x11 = win;
        xdg->configured = true;
        xs->surface->role_data = xdg;
        win->xs = xs;
        win->xdg = xdg;
        if (win->popup)
            attach_popup(win, xdg);
        else
            attach_toplevel(win, xdg);
        return;
    }
}

static void detach(struct dnc_xwl_window *win)
{
    struct dnc_server *srv = win->server;
    struct dnc_xdg_surface *xdg = win->xdg;
    if (!xdg)
        return;
    if (xdg->toplevel) {
        // Popups shown on this toplevel go with it
        struct dnc_xwl_window *w;
        wl_list_for_each(w, &srv->xwl_windows, link) {
            if (w->popup && w->popup_data && w->popup_data->parent == xdg)
                detach(w);
        }
        dnc_toplevel_destroy(xdg->toplevel);
    }
    if (win->popup_data) {
        struct dnc_popup *popup = win->popup_data;
        struct dnc_toplevel *tl = popup->parent && popup->parent->toplevel
                                  ? popup->parent->toplevel : NULL;
        wl_list_remove(&popup->link);
        free(popup);
        win->popup_data = NULL;
        if (tl)
            dnc_toplevel_schedule_repaint(tl);
    }
    if (xdg->surface) {
        xdg->surface->role = DNC_ROLE_NONE;
        xdg->surface->role_data = NULL;
        xdg->surface->mapped = false;
    }
    if (srv->xwl_active == win)
        srv->xwl_active = NULL;
    free(xdg);
    win->xdg = NULL;
    win->xs = NULL;
}

static void window_free(struct dnc_xwl_window *win)
{
    detach(win);
    wl_list_remove(&win->link);
    free(win->title);
    free(win->cls);
    free(win);
}

/* Compositor -> dn-xwm */

void dnc_xwayland_configure(struct dnc_toplevel *tl)
{
    struct dnc_server *srv = tl->server;
    struct dnc_xwl_window *win = tl->xdg ? tl->xdg->x11 : NULL;
    if (!win)
        return;
    if (tl->activated)
        srv->xwl_active = win;
    if (!srv->xwm)
        return;
    // Without a host window the X window keeps its own size
    int32_t w = tl->window ? tl->configured_w : 0;
    int32_t h = tl->window ? tl->configured_h : 0;
    dn_xwm_v1_send_configure(srv->xwm, win->window, w, h, tl->activated ? 1 : 0);
}

void dnc_xwayland_close(struct dnc_toplevel *tl)
{
    struct dnc_xwl_window *win = tl->xdg ? tl->xdg->x11 : NULL;
    if (win && tl->server->xwm)
        dn_xwm_v1_send_close(tl->server->xwm, win->window);
}

/* dn_xwm_v1 */

static struct dnc_xwl_window *new_window(struct dnc_server *srv, uint32_t id,
                                         uint32_t lo, uint32_t hi, bool popup)
{
    struct dnc_xwl_window *win = window_by_id(srv, id);
    if (win) {
        // Re-mapped with a new surface
        detach(win);
    } else {
        win = calloc(1, sizeof(*win));
        if (!win)
            return NULL;
        win->server = srv;
        win->window = id;
        wl_list_insert(&srv->xwl_windows, &win->link);
    }
    win->serial = ((uint64_t)hi << 32) | lo;
    win->popup = popup;
    return win;
}

static void xwm_manage_toplevel(struct wl_client *c, struct wl_resource *r,
                                uint32_t window, uint32_t lo, uint32_t hi,
                                int32_t width, int32_t height)
{
    (void)c;
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct dnc_xwl_window *win = new_window(srv, window, lo, hi, false);
    if (!win)
        return;
    win->x = win->y = 0;
    win->width = width;
    win->height = height;
    dnc_log(srv, DNC_LOG_DEBUG, "X toplevel 0x%x serial %llu %dx%d", window,
            (unsigned long long)win->serial, width, height);
    // The newest toplevel is where the user works until a focus change says otherwise
    srv->xwl_active = win;
    try_attach(win);
}

static void xwm_manage_popup(struct wl_client *c, struct wl_resource *r,
                             uint32_t window, uint32_t lo, uint32_t hi,
                             int32_t x, int32_t y, int32_t width, int32_t height)
{
    (void)c;
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct dnc_xwl_window *win = new_window(srv, window, lo, hi, true);
    if (!win)
        return;
    win->x = x;
    win->y = y;
    win->width = width;
    win->height = height;
    try_attach(win);
}

static void xwm_move_popup(struct wl_client *c, struct wl_resource *r,
                           uint32_t window, int32_t x, int32_t y,
                           int32_t width, int32_t height)
{
    (void)c;
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct dnc_xwl_window *win = window_by_id(srv, window);
    if (!win || !win->popup)
        return;
    win->x = x;
    win->y = y;
    win->width = width;
    win->height = height;
    struct dnc_popup *popup = win->popup_data;
    if (popup && popup->parent && popup->parent->x11) {
        popup->x = x - popup->parent->x11->x;
        popup->y = y - popup->parent->x11->y;
        popup->width = width;
        popup->height = height;
        if (popup->parent->toplevel)
            dnc_toplevel_schedule_repaint(popup->parent->toplevel);
    }
}

static void notify_changed(struct dnc_xwl_window *win)
{
    struct dnc_server *srv = win->server;
    struct dnc_toplevel *tl = win->xdg ? win->xdg->toplevel : NULL;
    if (!tl)
        return;
    free(tl->title);
    free(tl->app_id);
    tl->title = win->title ? strdup(win->title) : NULL;
    tl->app_id = win->cls ? strdup(win->cls) : NULL;
    if (tl->announced && srv->callbacks.toplevel_changed)
        srv->callbacks.toplevel_changed(srv->callback_data, tl->id,
                                        tl->app_id ? tl->app_id : "",
                                        tl->title ? tl->title : "");
}

static struct dnc_xwl_window *window_for_props(struct dnc_server *srv, uint32_t id)
{
    // Properties may arrive before manage: keep them on a placeholder
    struct dnc_xwl_window *win = window_by_id(srv, id);
    if (!win) {
        win = calloc(1, sizeof(*win));
        if (!win)
            return NULL;
        win->server = srv;
        win->window = id;
        wl_list_insert(&srv->xwl_windows, &win->link);
    }
    return win;
}

static void xwm_set_title(struct wl_client *c, struct wl_resource *r,
                          uint32_t window, const char *title)
{
    (void)c;
    struct dnc_xwl_window *win = window_for_props(wl_resource_get_user_data(r), window);
    if (!win)
        return;
    free(win->title);
    win->title = strdup(title);
    notify_changed(win);
}

static void xwm_set_class(struct wl_client *c, struct wl_resource *r,
                          uint32_t window, const char *cls)
{
    (void)c;
    struct dnc_xwl_window *win = window_for_props(wl_resource_get_user_data(r), window);
    if (!win)
        return;
    free(win->cls);
    win->cls = strdup(cls);
    notify_changed(win);
}

static void xwm_set_pid(struct wl_client *c, struct wl_resource *r,
                        uint32_t window, int32_t pid)
{
    (void)c;
    struct dnc_xwl_window *win = window_for_props(wl_resource_get_user_data(r), window);
    if (win)
        win->pid = pid;
}

static void xwm_unmanage(struct wl_client *c, struct wl_resource *r, uint32_t window)
{
    (void)c;
    struct dnc_xwl_window *win = window_by_id(wl_resource_get_user_data(r), window);
    if (win)
        window_free(win);
}

static const struct dn_xwm_v1_interface xwm_impl = {
    .manage_toplevel = xwm_manage_toplevel,
    .manage_popup = xwm_manage_popup,
    .move_popup = xwm_move_popup,
    .set_title = xwm_set_title,
    .set_class = xwm_set_class,
    .set_pid = xwm_set_pid,
    .unmanage = xwm_unmanage,
};

static void xwm_resource_destroy(struct wl_resource *r)
{
    struct dnc_server *srv = wl_resource_get_user_data(r);
    if (srv->xwm != r)
        return;
    srv->xwm = NULL;
    // dn-xwm is gone (Xwayland stopped): its windows go too
    struct dnc_xwl_window *win, *tmp;
    wl_list_for_each_safe(win, tmp, &srv->xwl_windows, link)
        window_free(win);
}

static void bind_xwm(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct dnc_server *srv = data;
    struct wl_resource *r = wl_resource_create(c, &dn_xwm_v1_interface, (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    if (srv->xwm) {
        dnc_log(srv, DNC_LOG_WARN, "second X window manager refused");
        wl_resource_destroy(r);
        return;
    }
    srv->xwm = r;
    wl_resource_set_implementation(r, &xwm_impl, srv, xwm_resource_destroy);
    dnc_log(srv, DNC_LOG_INFO, "X window manager connected");
}

/* xwayland_shell_v1 */

static void xs_surface_destroyed(struct wl_listener *l, void *data)
{
    struct xwl_surface *xs = wl_container_of(l, xs, surface_destroy);
    (void)data;
    struct dnc_xwl_window *win;
    wl_list_for_each(win, &xs->server->xwl_windows, link) {
        if (win->xs == xs)
            detach(win);
    }
    xs->surface = NULL;
    wl_list_remove(&xs->surface_destroy.link);
    wl_list_init(&xs->surface_destroy.link);
}

static void xs_set_serial(struct wl_client *c, struct wl_resource *r,
                          uint32_t lo, uint32_t hi)
{
    (void)c;
    struct xwl_surface *xs = wl_resource_get_user_data(r);
    xs->serial = ((uint64_t)hi << 32) | lo;
    xs->has_serial = true;
    dnc_log(xs->server, DNC_LOG_DEBUG, "xwayland surface serial %llu", (unsigned long long)xs->serial);
    struct dnc_xwl_window *win;
    wl_list_for_each(win, &xs->server->xwl_windows, link) {
        if (win->serial == xs->serial && !win->xdg)
            try_attach(win);
    }
}

static void xs_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static const struct xwayland_surface_v1_interface xs_impl = {
    .set_serial = xs_set_serial,
    .destroy = xs_destroy,
};

static void xs_resource_destroy(struct wl_resource *r)
{
    struct xwl_surface *xs = wl_resource_get_user_data(r);
    struct dnc_xwl_window *win;
    wl_list_for_each(win, &xs->server->xwl_windows, link) {
        if (win->xs == xs)
            win->xs = NULL;
    }
    wl_list_remove(&xs->surface_destroy.link);
    wl_list_remove(&xs->link);
    free(xs);
}

static void shell_get_surface(struct wl_client *c, struct wl_resource *r,
                              uint32_t id, struct wl_resource *surface)
{
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct xwl_surface *xs = calloc(1, sizeof(*xs));
    if (!xs) {
        wl_client_post_no_memory(c);
        return;
    }
    xs->resource = wl_resource_create(c, &xwayland_surface_v1_interface,
                                      wl_resource_get_version(r), id);
    if (!xs->resource) {
        free(xs);
        wl_client_post_no_memory(c);
        return;
    }
    xs->server = srv;
    xs->surface = dnc_surface_from_resource(surface);
    xs->surface_destroy.notify = xs_surface_destroyed;
    wl_resource_add_destroy_listener(surface, &xs->surface_destroy);
    wl_list_insert(&srv->xwl_surfaces, &xs->link);
    wl_resource_set_implementation(xs->resource, &xs_impl, xs, xs_resource_destroy);
}

static void shell_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static const struct xwayland_shell_v1_interface shell_impl = {
    .destroy = shell_destroy,
    .get_xwayland_surface = shell_get_surface,
};

static void bind_shell(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct wl_resource *r = wl_resource_create(c, &xwayland_shell_v1_interface,
                                               (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &shell_impl, data, NULL);
}

bool dnc_xwayland_init(struct dnc_server *srv)
{
    wl_list_init(&srv->xwl_surfaces);
    wl_list_init(&srv->xwl_windows);
    // Not restricted to Xwayland: under PRoot there is no security boundary
    // between guest programs anyway
    return wl_global_create(srv->display, &xwayland_shell_v1_interface, 1, srv, bind_shell) &&
           wl_global_create(srv->display, &dn_xwm_v1_interface, 1, srv, bind_xwm);
}

void dnc_xwayland_finish(struct dnc_server *srv)
{
    struct dnc_xwl_window *win, *tmp;
    wl_list_for_each_safe(win, tmp, &srv->xwl_windows, link)
        window_free(win);
}
