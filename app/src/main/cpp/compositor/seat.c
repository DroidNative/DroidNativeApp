/* wl_seat with pointer, keyboard and touch. Input comes from the host window. */
#define _GNU_SOURCE
#include <stdlib.h>
#include <unistd.h>
#include <wayland-server-protocol.h>

#include "dnc_private.h"
#include "xdg-shell-server-protocol.h"

#define SEAT_VERSION 7
#define BTN_LEFT 0x110

/* Hit testing */

struct hit {
    struct dnc_surface *surface;
    double sx, sy;              // surface-local, logical
};

static bool hit_tree(struct dnc_surface *s, double x, double y, struct hit *out)
{
    // Subsurfaces above the parent are checked first (front to back)
    struct dnc_subsurface *sub;
    wl_list_for_each(sub, &s->subsurfaces, link) {
        if (hit_tree(sub->surface, x - sub->x, y - sub->y, out))
            return true;
    }
    if (s->mapped && x >= 0 && y >= 0 && x < dnc_surface_width(s) &&
        y < dnc_surface_height(s)) {
        out->surface = s;
        out->sx = x;
        out->sy = y;
        return true;
    }
    return false;
}

// x, y are window pixels
static bool hit_toplevel(struct dnc_server *srv, struct dnc_toplevel *tl,
                         double x, double y, struct hit *out)
{
    if (!tl->xdg || !tl->xdg->surface)
        return false;
    double lx = x / srv->config.scale, ly = y / srv->config.scale;
    double ox = tl->xdg->has_geometry ? -tl->xdg->geo_x : 0;
    double oy = tl->xdg->has_geometry ? -tl->xdg->geo_y : 0;

    struct dnc_popup *popup;
    wl_list_for_each_reverse(popup, &tl->popups, link) {
        if (!popup->xdg || !popup->xdg->surface)
            continue;
        double px = popup->x, py = popup->y;
        for (struct dnc_xdg_surface *p = popup->parent; p && p->popup;
             p = p->popup->parent) {
            px += p->popup->x;
            py += p->popup->y;
        }
        double gx = popup->xdg->has_geometry ? popup->xdg->geo_x : 0;
        double gy = popup->xdg->has_geometry ? popup->xdg->geo_y : 0;
        if (hit_tree(popup->xdg->surface, lx - (ox + px - gx), ly - (oy + py - gy), out))
            return true;
    }
    if (hit_tree(tl->xdg->surface, lx - ox, ly - oy, out))
        return true;
    // Outside every surface: clamp to the root so input is not lost
    out->surface = tl->xdg->surface;
    out->sx = lx - ox;
    out->sy = ly - oy;
    return false;
}

// Position of s in the toplevel's window, logical units
static bool surface_origin(struct dnc_toplevel *tl, struct dnc_surface *s,
                           double *ox, double *oy, int depth)
{
    if (!s || depth > 32 || !tl->xdg)
        return false;
    double rx = tl->xdg->has_geometry ? -tl->xdg->geo_x : 0;
    double ry = tl->xdg->has_geometry ? -tl->xdg->geo_y : 0;
    if (s == tl->xdg->surface) {
        *ox = rx;
        *oy = ry;
        return true;
    }
    if (s->role == DNC_ROLE_SUBSURFACE) {
        struct dnc_subsurface *sub = s->role_data;
        if (!sub || !surface_origin(tl, sub->parent, ox, oy, depth + 1))
            return false;
        *ox += sub->x;
        *oy += sub->y;
        return true;
    }
    if (s->role == DNC_ROLE_XDG_POPUP) {
        struct dnc_xdg_surface *xdg = s->role_data;
        if (!xdg || !xdg->popup)
            return false;
        double px = xdg->popup->x, py = xdg->popup->y;
        for (struct dnc_xdg_surface *p = xdg->popup->parent; p && p->popup;
             p = p->popup->parent) {
            px += p->popup->x;
            py += p->popup->y;
        }
        *ox = rx + px - (xdg->has_geometry ? xdg->geo_x : 0);
        *oy = ry + py - (xdg->has_geometry ? xdg->geo_y : 0);
        return true;
    }
    return false;
}

// Window pixels -> coordinates local to s (used during grabs)
static void to_surface_local(struct dnc_server *srv, struct dnc_toplevel *tl,
                             struct dnc_surface *s, double x, double y,
                             double *sx, double *sy)
{
    double ox = 0, oy = 0;
    surface_origin(tl, s, &ox, &oy, 0);
    *sx = x / srv->config.scale - ox;
    *sy = y / srv->config.scale - oy;
}

static bool surface_in_popup(struct dnc_toplevel *tl, struct dnc_surface *s)
{
    struct dnc_popup *popup;
    wl_list_for_each(popup, &tl->popups, link) {
        if (popup->xdg && popup->xdg->surface == s)
            return true;
    }
    return false;
}

/* Resource helpers */

static struct wl_client *surface_client(struct dnc_surface *s)
{
    return s ? wl_resource_get_client(s->resource) : NULL;
}

#define for_each_client_resource(res, list, client) \
    wl_resource_for_each(res, list) \
        if (wl_resource_get_client(res) == (client))

static void focus_destroyed_pointer(struct wl_listener *l, void *data)
{
    struct dnc_seat_state *seat = wl_container_of(l, seat, pointer_focus_destroy);
    (void)data;
    seat->pointer_focus = NULL;
    wl_list_remove(&l->link);
    wl_list_init(&l->link);
}

static void focus_destroyed_keyboard(struct wl_listener *l, void *data)
{
    struct dnc_seat_state *seat = wl_container_of(l, seat, keyboard_focus_destroy);
    struct dnc_server *srv = wl_container_of(seat, srv, seat);
    (void)data;
    seat->keyboard_focus = NULL;
    wl_list_remove(&l->link);
    wl_list_init(&l->link);
    dnc_text_input_focus_lost(srv);
}

void dnc_seat_surface_destroyed(struct dnc_server *srv, struct dnc_surface *s)
{
    struct dnc_seat_state *seat = &srv->seat;
    for (size_t i = 0; i < DNC_ARRAY_LEN(seat->touch_focus); i++) {
        if (seat->touch_focus[i] == s)
            seat->touch_focus[i] = NULL;
    }
}

/* Pointer */

static void pointer_frame(struct wl_resource *res)
{
    if (wl_resource_get_version(res) >= WL_POINTER_FRAME_SINCE_VERSION)
        wl_pointer_send_frame(res);
}

static void pointer_set_focus(struct dnc_server *srv, struct dnc_surface *s,
                              double sx, double sy)
{
    struct dnc_seat_state *seat = &srv->seat;
    if (seat->pointer_focus == s)
        return;
    struct wl_resource *res;
    if (seat->pointer_focus) {
        uint32_t serial = wl_display_next_serial(srv->display);
        for_each_client_resource(res, &seat->pointers, surface_client(seat->pointer_focus)) {
            wl_pointer_send_leave(res, serial, seat->pointer_focus->resource);
            pointer_frame(res);
        }
        wl_list_remove(&seat->pointer_focus_destroy.link);
        wl_list_init(&seat->pointer_focus_destroy.link);
    }
    seat->pointer_focus = s;
    if (s) {
        wl_resource_add_destroy_listener(s->resource, &seat->pointer_focus_destroy);
        uint32_t serial = wl_display_next_serial(srv->display);
        for_each_client_resource(res, &seat->pointers, surface_client(s)) {
            wl_pointer_send_enter(res, serial, s->resource,
                                  wl_fixed_from_double(sx), wl_fixed_from_double(sy));
            pointer_frame(res);
        }
    }
}

void dnc_seat_pointer_focus_surface(struct dnc_server *srv, struct dnc_surface *s)
{
    pointer_set_focus(srv, s, srv->seat.pointer_sx, srv->seat.pointer_sy);
}

void dnc_seat_pointer_relative(struct dnc_server *srv, struct dnc_toplevel *tl,
                               double dx, double dy, uint32_t time)
{
    (void)tl;
    // Host pixels to surface coordinates
    double s = srv->config.scale > 0 ? srv->config.scale : 1;
    dnc_relative_motion(srv, dx / s, dy / s, time);
    struct wl_resource *res;
    if (srv->seat.pointer_focus)
        for_each_client_resource(res, &srv->seat.pointers, surface_client(srv->seat.pointer_focus))
            pointer_frame(res);
}

void dnc_seat_pointer_motion(struct dnc_server *srv, struct dnc_toplevel *tl,
                             double x, double y, uint32_t time)
{
    struct dnc_seat_state *seat = &srv->seat;
    // A locked pointer does not move; the host sends relative motion
    if (dnc_pointer_locked(srv))
        return;
    double old_sx = seat->pointer_sx, old_sy = seat->pointer_sy;
    struct dnc_surface *old_focus = seat->pointer_focus;
    struct hit hit = { 0 };
    hit_toplevel(srv, tl, x, y, &hit);

    // Implicit grab: while a button is held the focus does not change
    if (seat->buttons_down && seat->pointer_focus) {
        if (hit.surface != seat->pointer_focus) {
            hit.surface = seat->pointer_focus;
            to_surface_local(srv, tl, hit.surface, x, y, &hit.sx, &hit.sy);
        }
    } else {
        pointer_set_focus(srv, hit.surface, hit.sx, hit.sy);
    }
    seat->pointer_sx = hit.sx;
    seat->pointer_sy = hit.sy;
    if (!seat->pointer_focus)
        return;
    if (seat->pointer_focus == old_focus)
        dnc_relative_motion(srv, hit.sx - old_sx, hit.sy - old_sy, time);
    struct wl_resource *res;
    for_each_client_resource(res, &seat->pointers, surface_client(seat->pointer_focus)) {
        wl_pointer_send_motion(res, time, wl_fixed_from_double(hit.sx),
                               wl_fixed_from_double(hit.sy));
        pointer_frame(res);
    }
}

void dnc_seat_pointer_button(struct dnc_server *srv, struct dnc_toplevel *tl,
                             uint32_t button, bool pressed, uint32_t time)
{
    struct dnc_seat_state *seat = &srv->seat;
    if (pressed) {
        // A press outside the open popups dismisses them
        if (!wl_list_empty(&tl->popups) &&
            (!seat->pointer_focus || !surface_in_popup(tl, seat->pointer_focus)))
            dnc_toplevel_dismiss_popups(tl);
        seat->buttons_down++;
        dnc_seat_keyboard_focus(srv, tl);
    } else if (seat->buttons_down > 0) {
        seat->buttons_down--;
    }
    if (!seat->pointer_focus)
        return;
    uint32_t serial = wl_display_next_serial(srv->display);
    struct wl_resource *res;
    for_each_client_resource(res, &seat->pointers, surface_client(seat->pointer_focus)) {
        wl_pointer_send_button(res, serial, time, button,
                               pressed ? WL_POINTER_BUTTON_STATE_PRESSED
                                       : WL_POINTER_BUTTON_STATE_RELEASED);
        pointer_frame(res);
    }
}

void dnc_seat_pointer_axis(struct dnc_server *srv, struct dnc_toplevel *tl,
                           double dx, double dy, uint32_t time)
{
    (void)tl;
    struct dnc_seat_state *seat = &srv->seat;
    if (!seat->pointer_focus)
        return;
    struct wl_resource *res;
    for_each_client_resource(res, &seat->pointers, surface_client(seat->pointer_focus)) {
        if (dy != 0)
            wl_pointer_send_axis(res, time, WL_POINTER_AXIS_VERTICAL_SCROLL,
                                 wl_fixed_from_double(dy));
        if (dx != 0)
            wl_pointer_send_axis(res, time, WL_POINTER_AXIS_HORIZONTAL_SCROLL,
                                 wl_fixed_from_double(dx));
        pointer_frame(res);
    }
}

// Mouse wheel notches (emulated by touch gestures): Xwayland and toolkits
// that scroll by lines need the discrete steps, not only a distance
void dnc_seat_pointer_wheel(struct dnc_server *srv, struct dnc_toplevel *tl,
                            int32_t steps_x, int32_t steps_y, uint32_t time)
{
    (void)tl;
    struct dnc_seat_state *seat = &srv->seat;
    if (!seat->pointer_focus)
        return;
    struct wl_resource *res;
    for_each_client_resource(res, &seat->pointers, surface_client(seat->pointer_focus)) {
        bool v5 = wl_resource_get_version(res) >= WL_POINTER_AXIS_SOURCE_SINCE_VERSION;
        if (v5)
            wl_pointer_send_axis_source(res, WL_POINTER_AXIS_SOURCE_WHEEL);
        if (steps_y) {
            if (v5)
                wl_pointer_send_axis_discrete(res, WL_POINTER_AXIS_VERTICAL_SCROLL, steps_y);
            wl_pointer_send_axis(res, time, WL_POINTER_AXIS_VERTICAL_SCROLL,
                                 wl_fixed_from_int(15 * steps_y));
        }
        if (steps_x) {
            if (v5)
                wl_pointer_send_axis_discrete(res, WL_POINTER_AXIS_HORIZONTAL_SCROLL, steps_x);
            wl_pointer_send_axis(res, time, WL_POINTER_AXIS_HORIZONTAL_SCROLL,
                                 wl_fixed_from_int(15 * steps_x));
        }
        pointer_frame(res);
    }
}

/* Touch */

static void touch_native(struct dnc_server *srv, struct dnc_toplevel *tl,
                         enum dnc_touch_action action, int32_t slot,
                         double x, double y, uint32_t time)
{
    struct dnc_seat_state *seat = &srv->seat;
    if (slot < 0 || slot >= (int32_t)DNC_ARRAY_LEN(seat->touch_focus))
        return;
    struct wl_resource *res;
    struct hit hit = { 0 };

    switch (action) {
    case DNC_TOUCH_DOWN: {
        hit_toplevel(srv, tl, x, y, &hit);
        if (!hit.surface)
            return;
        if (!wl_list_empty(&tl->popups) && !surface_in_popup(tl, hit.surface))
            dnc_toplevel_dismiss_popups(tl);
        dnc_seat_keyboard_focus(srv, tl);
        seat->touch_focus[slot] = hit.surface;
        uint32_t serial = wl_display_next_serial(srv->display);
        for_each_client_resource(res, &seat->touches, surface_client(hit.surface)) {
            wl_touch_send_down(res, serial, time, hit.surface->resource, slot,
                               wl_fixed_from_double(hit.sx), wl_fixed_from_double(hit.sy));
            wl_touch_send_frame(res);
        }
        break;
    }
    case DNC_TOUCH_MOTION: {
        struct dnc_surface *s = seat->touch_focus[slot];
        if (!s)
            return;
        // Coordinates stay relative to the surface the touch started on
        double sx, sy;
        to_surface_local(srv, tl, s, x, y, &sx, &sy);
        for_each_client_resource(res, &seat->touches, surface_client(s)) {
            wl_touch_send_motion(res, time, slot, wl_fixed_from_double(sx),
                                 wl_fixed_from_double(sy));
            wl_touch_send_frame(res);
        }
        break;
    }
    case DNC_TOUCH_UP: {
        struct dnc_surface *s = seat->touch_focus[slot];
        seat->touch_focus[slot] = NULL;
        if (!s)
            return;
        uint32_t serial = wl_display_next_serial(srv->display);
        for_each_client_resource(res, &seat->touches, surface_client(s)) {
            wl_touch_send_up(res, serial, time, slot);
            wl_touch_send_frame(res);
        }
        break;
    }
    case DNC_TOUCH_CANCEL:
        for (size_t i = 0; i < DNC_ARRAY_LEN(seat->touch_focus); i++) {
            struct dnc_surface *s = seat->touch_focus[i];
            if (!s)
                continue;
            for_each_client_resource(res, &seat->touches, surface_client(s))
                wl_touch_send_cancel(res);
            seat->touch_focus[i] = NULL;
        }
        break;
    }
}

void dnc_seat_touch(struct dnc_server *srv, struct dnc_toplevel *tl,
                    enum dnc_touch_action action, int32_t slot,
                    double x, double y, uint32_t time)
{
    if (srv->config.touch_mode == DNC_TOUCH_NATIVE) {
        touch_native(srv, tl, action, slot, x, y, time);
        return;
    }
    // Pointer emulation: only the first finger drives the pointer
    if (slot != 0)
        return;
    switch (action) {
    case DNC_TOUCH_DOWN:
        dnc_seat_pointer_motion(srv, tl, x, y, time);
        dnc_seat_pointer_button(srv, tl, BTN_LEFT, true, time);
        break;
    case DNC_TOUCH_MOTION:
        dnc_seat_pointer_motion(srv, tl, x, y, time);
        break;
    case DNC_TOUCH_UP:
    case DNC_TOUCH_CANCEL:
        dnc_seat_pointer_button(srv, tl, BTN_LEFT, false, time);
        break;
    }
}

/* Keyboard */

static void keyboard_send_modifiers(struct dnc_server *srv, struct wl_resource *res,
                                    uint32_t serial)
{
    wl_keyboard_send_modifiers(res, serial, srv->seat.mods_depressed, 0,
                               srv->seat.mods_locked, 0);
}

void dnc_seat_keyboard_focus(struct dnc_server *srv, struct dnc_toplevel *tl)
{
    struct dnc_seat_state *seat = &srv->seat;
    struct dnc_surface *s = tl && tl->xdg ? tl->xdg->surface : NULL;
    if (seat->keyboard_focus == s)
        return;
    struct wl_resource *res;
    struct dnc_surface *old = seat->keyboard_focus;
    if (old) {
        uint32_t serial = wl_display_next_serial(srv->display);
        for_each_client_resource(res, &seat->keyboards, surface_client(old))
            wl_keyboard_send_leave(res, serial, old->resource);
        wl_list_remove(&seat->keyboard_focus_destroy.link);
        wl_list_init(&seat->keyboard_focus_destroy.link);
    }
    seat->keyboard_focus = s;
    // text-input enter must follow wl_keyboard.enter, leave precede nothing
    if (!s) {
        dnc_text_input_focus(srv, old, NULL);
        dnc_constraints_update(srv);
        return;
    }
    wl_resource_add_destroy_listener(s->resource, &seat->keyboard_focus_destroy);
    struct wl_array keys;
    wl_array_init(&keys);
    uint32_t serial = wl_display_next_serial(srv->display);
    for_each_client_resource(res, &seat->keyboards, surface_client(s)) {
        wl_keyboard_send_enter(res, serial, s->resource, &keys);
        keyboard_send_modifiers(srv, res, serial);
    }
    wl_array_release(&keys);
    dnc_text_input_focus(srv, old, s);
    dnc_constraints_update(srv);
}

void dnc_seat_key(struct dnc_server *srv, struct dnc_toplevel *tl, uint32_t key,
                  bool pressed, uint32_t mods_depressed, uint32_t mods_locked,
                  uint32_t time)
{
    struct dnc_seat_state *seat = &srv->seat;
    if (!seat->keyboard_focus)
        dnc_seat_keyboard_focus(srv, tl);
    if (!seat->keyboard_focus)
        return;
    struct wl_resource *res;
    struct wl_client *client = surface_client(seat->keyboard_focus);
    bool mods_changed = mods_depressed != seat->mods_depressed ||
                        mods_locked != seat->mods_locked;
    seat->mods_depressed = mods_depressed;
    seat->mods_locked = mods_locked;
    uint32_t serial = wl_display_next_serial(srv->display);
    for_each_client_resource(res, &seat->keyboards, client) {
        // Modifiers first so the key is interpreted with the new state
        if (mods_changed)
            keyboard_send_modifiers(srv, res, serial);
        wl_keyboard_send_key(res, serial, time, key,
                             pressed ? WL_KEYBOARD_KEY_STATE_PRESSED
                                     : WL_KEYBOARD_KEY_STATE_RELEASED);
    }
}

void dnc_seat_keymap_changed(struct dnc_server *srv)
{
    struct dnc_seat_state *seat = &srv->seat;
    struct wl_resource *res;
    wl_resource_for_each(res, &seat->keyboards) {
        wl_keyboard_send_keymap(res, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1,
                                seat->keymap_fd, seat->keymap_size);
        // Clients rebuild their state from the new keymap: resend modifiers
        if (seat->keyboard_focus &&
            wl_resource_get_client(res) == wl_resource_get_client(seat->keyboard_focus->resource))
            keyboard_send_modifiers(srv, res, wl_display_next_serial(srv->display));
    }
}

/* wl_seat */

static void resource_unlink(struct wl_resource *res)
{
    wl_list_remove(wl_resource_get_link(res));
}

static void release_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void pointer_set_cursor(struct wl_client *c, struct wl_resource *r,
                               uint32_t serial, struct wl_resource *surface,
                               int32_t hx, int32_t hy)
{
    // Cursors are not drawn (touch device); accept the role only
    (void)c; (void)r; (void)serial; (void)hx; (void)hy;
    struct dnc_surface *s = dnc_surface_from_resource(surface);
    if (s && s->role == DNC_ROLE_NONE)
        s->role = DNC_ROLE_CURSOR;
}

static const struct wl_pointer_interface pointer_impl = {
    .set_cursor = pointer_set_cursor,
    .release = release_req,
};

static const struct wl_keyboard_interface keyboard_impl = {
    .release = release_req,
};

static const struct wl_touch_interface touch_impl = {
    .release = release_req,
};

static void seat_get_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct wl_resource *res = wl_resource_create(c, &wl_pointer_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(res, &pointer_impl, srv, resource_unlink);
    wl_list_insert(&srv->seat.pointers, wl_resource_get_link(res));
}

static void seat_get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct wl_resource *res = wl_resource_create(c, &wl_keyboard_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(res, &keyboard_impl, srv, resource_unlink);
    wl_list_insert(&srv->seat.keyboards, wl_resource_get_link(res));

    if (srv->seat.keymap_fd >= 0)
        wl_keyboard_send_keymap(res, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1,
                                srv->seat.keymap_fd, srv->seat.keymap_size);
    else
        wl_keyboard_send_keymap(res, WL_KEYBOARD_KEYMAP_FORMAT_NO_KEYMAP, -1, 0);
    if (wl_resource_get_version(res) >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION)
        wl_keyboard_send_repeat_info(res, 25, 400);

    // Late keyboard of an already focused client
    struct dnc_surface *focus = srv->seat.keyboard_focus;
    if (focus && surface_client(focus) == c) {
        struct wl_array keys;
        wl_array_init(&keys);
        uint32_t serial = wl_display_next_serial(srv->display);
        wl_keyboard_send_enter(res, serial, focus->resource, &keys);
        keyboard_send_modifiers(srv, res, serial);
        wl_array_release(&keys);
    }
}

static void seat_get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct wl_resource *res = wl_resource_create(c, &wl_touch_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(res, &touch_impl, srv, resource_unlink);
    wl_list_insert(&srv->seat.touches, wl_resource_get_link(res));
}

static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer,
    .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch,
    .release = release_req,
};

static void bind_seat(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct dnc_server *srv = data;
    struct wl_resource *r = wl_resource_create(c, &wl_seat_interface, (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &seat_impl, srv, resource_unlink);
    wl_list_insert(&srv->seat.seat_resources, wl_resource_get_link(r));

    uint32_t caps = WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_TOUCH;
    if (srv->seat.keymap_fd >= 0)
        caps |= WL_SEAT_CAPABILITY_KEYBOARD;
    wl_seat_send_capabilities(r, caps);
    if (version >= WL_SEAT_NAME_SINCE_VERSION)
        wl_seat_send_name(r, "seat0");
}

bool dnc_seat_init(struct dnc_server *srv)
{
    struct dnc_seat_state *seat = &srv->seat;
    wl_list_init(&seat->seat_resources);
    wl_list_init(&seat->pointers);
    wl_list_init(&seat->keyboards);
    wl_list_init(&seat->touches);
    wl_list_init(&seat->pointer_focus_destroy.link);
    wl_list_init(&seat->keyboard_focus_destroy.link);
    seat->pointer_focus_destroy.notify = focus_destroyed_pointer;
    seat->keyboard_focus_destroy.notify = focus_destroyed_keyboard;
    return wl_global_create(srv->display, &wl_seat_interface, SEAT_VERSION, srv,
                            bind_seat) != NULL;
}

void dnc_seat_finish(struct dnc_server *srv)
{
    if (srv->seat.keymap_fd >= 0)
        close(srv->seat.keymap_fd);
    srv->seat.keymap_fd = -1;
}
