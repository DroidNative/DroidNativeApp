/* xdg-shell: xdg_wm_base, xdg_positioner, xdg_surface, xdg_toplevel, xdg_popup */
#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>

#include "dnc_private.h"
#include "xdg-shell-server-protocol.h"

#define XDG_WM_BASE_VERSION 6

/* Positioner */

struct dnc_positioner {
    int32_t width, height;
    int32_t ax, ay, aw, ah;       // anchor rect
    uint32_t anchor, gravity;
    int32_t off_x, off_y;
};

static void positioner_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void positioner_set_size(struct wl_client *c, struct wl_resource *r,
                                int32_t w, int32_t h)
{
    (void)c;
    struct dnc_positioner *p = wl_resource_get_user_data(r);
    if (w < 1 || h < 1) {
        wl_resource_post_error(r, XDG_POSITIONER_ERROR_INVALID_INPUT, "invalid size");
        return;
    }
    p->width = w;
    p->height = h;
}

static void positioner_set_anchor_rect(struct wl_client *c, struct wl_resource *r,
                                       int32_t x, int32_t y, int32_t w, int32_t h)
{
    (void)c;
    struct dnc_positioner *p = wl_resource_get_user_data(r);
    if (w < 0 || h < 0) {
        wl_resource_post_error(r, XDG_POSITIONER_ERROR_INVALID_INPUT, "invalid anchor rect");
        return;
    }
    p->ax = x; p->ay = y; p->aw = w; p->ah = h;
}

static void positioner_set_anchor(struct wl_client *c, struct wl_resource *r,
                                  uint32_t anchor)
{
    (void)c;
    struct dnc_positioner *p = wl_resource_get_user_data(r);
    p->anchor = anchor;
}

static void positioner_set_gravity(struct wl_client *c, struct wl_resource *r,
                                   uint32_t gravity)
{
    (void)c;
    struct dnc_positioner *p = wl_resource_get_user_data(r);
    p->gravity = gravity;
}

static void positioner_set_constraint(struct wl_client *c, struct wl_resource *r,
                                      uint32_t adj)
{
    (void)c; (void)r; (void)adj;
}

static void positioner_set_offset(struct wl_client *c, struct wl_resource *r,
                                  int32_t x, int32_t y)
{
    (void)c;
    struct dnc_positioner *p = wl_resource_get_user_data(r);
    p->off_x = x;
    p->off_y = y;
}

static void positioner_set_reactive(struct wl_client *c, struct wl_resource *r)
{
    (void)c; (void)r;
}

static void positioner_set_parent_size(struct wl_client *c, struct wl_resource *r,
                                       int32_t w, int32_t h)
{
    (void)c; (void)r; (void)w; (void)h;
}

static void positioner_set_parent_configure(struct wl_client *c,
                                            struct wl_resource *r, uint32_t s)
{
    (void)c; (void)r; (void)s;
}

static const struct xdg_positioner_interface positioner_impl = {
    .destroy = positioner_destroy,
    .set_size = positioner_set_size,
    .set_anchor_rect = positioner_set_anchor_rect,
    .set_anchor = positioner_set_anchor,
    .set_gravity = positioner_set_gravity,
    .set_constraint_adjustment = positioner_set_constraint,
    .set_offset = positioner_set_offset,
    .set_reactive = positioner_set_reactive,
    .set_parent_size = positioner_set_parent_size,
    .set_parent_configure = positioner_set_parent_configure,
};

static void positioner_resource_destroy(struct wl_resource *r)
{
    free(wl_resource_get_user_data(r));
}

// Position of the popup relative to the parent's window geometry
static void positioner_place(const struct dnc_positioner *p, int32_t *out_x,
                             int32_t *out_y)
{
    int32_t x = p->ax + p->aw / 2, y = p->ay + p->ah / 2;
    switch (p->anchor) {
    case XDG_POSITIONER_ANCHOR_TOP: y = p->ay; break;
    case XDG_POSITIONER_ANCHOR_BOTTOM: y = p->ay + p->ah; break;
    case XDG_POSITIONER_ANCHOR_LEFT: x = p->ax; break;
    case XDG_POSITIONER_ANCHOR_RIGHT: x = p->ax + p->aw; break;
    case XDG_POSITIONER_ANCHOR_TOP_LEFT: x = p->ax; y = p->ay; break;
    case XDG_POSITIONER_ANCHOR_BOTTOM_LEFT: x = p->ax; y = p->ay + p->ah; break;
    case XDG_POSITIONER_ANCHOR_TOP_RIGHT: x = p->ax + p->aw; y = p->ay; break;
    case XDG_POSITIONER_ANCHOR_BOTTOM_RIGHT:
        x = p->ax + p->aw; y = p->ay + p->ah; break;
    default: break;
    }

    switch (p->gravity) {
    case XDG_POSITIONER_GRAVITY_TOP: x -= p->width / 2; y -= p->height; break;
    case XDG_POSITIONER_GRAVITY_BOTTOM: x -= p->width / 2; break;
    case XDG_POSITIONER_GRAVITY_LEFT: x -= p->width; y -= p->height / 2; break;
    case XDG_POSITIONER_GRAVITY_RIGHT: y -= p->height / 2; break;
    case XDG_POSITIONER_GRAVITY_TOP_LEFT: x -= p->width; y -= p->height; break;
    case XDG_POSITIONER_GRAVITY_BOTTOM_LEFT: x -= p->width; break;
    case XDG_POSITIONER_GRAVITY_TOP_RIGHT: y -= p->height; break;
    case XDG_POSITIONER_GRAVITY_BOTTOM_RIGHT: break;
    default: x -= p->width / 2; y -= p->height / 2; break;
    }
    *out_x = x + p->off_x;
    *out_y = y + p->off_y;
}

/* Toplevel helpers */

struct dnc_toplevel *dnc_toplevel_by_id(struct dnc_server *srv, uint32_t id)
{
    struct dnc_toplevel *tl;
    wl_list_for_each(tl, &srv->toplevels, link) {
        if (tl->id == id)
            return tl;
    }
    return NULL;
}

static uint32_t next_serial(struct dnc_server *srv)
{
    return wl_display_next_serial(srv->display);
}

void dnc_toplevel_configure(struct dnc_toplevel *tl)
{
    if (tl->xdg && tl->xdg->x11) {
        dnc_xwayland_configure(tl);
        return;
    }
    if (!tl->xdg || !tl->xdg->role_resource)
        return;
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st;
    if (tl->window) {
        // Fills the Android window: maximized also makes CSD clients drop
        // their shadows and rounded corners.
        st = wl_array_add(&states, sizeof(*st));
        if (st)
            *st = XDG_TOPLEVEL_STATE_MAXIMIZED;
    }
    if (tl->activated) {
        st = wl_array_add(&states, sizeof(*st));
        if (st)
            *st = XDG_TOPLEVEL_STATE_ACTIVATED;
    }
    int32_t w = tl->window ? tl->configured_w : 0;
    int32_t h = tl->window ? tl->configured_h : 0;
    xdg_toplevel_send_configure(tl->xdg->role_resource, w, h, &states);
    wl_array_release(&states);
    tl->xdg->last_serial = next_serial(tl->server);
    xdg_surface_send_configure(tl->xdg->resource, tl->xdg->last_serial);
}

static void popup_dismiss(struct dnc_popup *popup)
{
    if (popup->xdg && popup->xdg->role_resource)
        xdg_popup_send_popup_done(popup->xdg->role_resource);
}

/* xdg_toplevel */

static void toplevel_destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void toplevel_notify_changed(struct dnc_toplevel *tl)
{
    struct dnc_server *srv = tl->server;
    if (tl->announced && srv->callbacks.toplevel_changed)
        srv->callbacks.toplevel_changed(srv->callback_data, tl->id,
                                        tl->app_id ? tl->app_id : "",
                                        tl->title ? tl->title : "");
}

static void toplevel_set_parent(struct wl_client *c, struct wl_resource *r,
                                struct wl_resource *parent)
{
    (void)c; (void)r; (void)parent;
}

static void toplevel_set_title(struct wl_client *c, struct wl_resource *r,
                               const char *title)
{
    (void)c;
    struct dnc_toplevel *tl = wl_resource_get_user_data(r);
    if (!tl)
        return;
    free(tl->title);
    tl->title = strdup(title);
    toplevel_notify_changed(tl);
}

static void toplevel_set_app_id(struct wl_client *c, struct wl_resource *r,
                                const char *app_id)
{
    (void)c;
    struct dnc_toplevel *tl = wl_resource_get_user_data(r);
    if (!tl)
        return;
    free(tl->app_id);
    tl->app_id = strdup(app_id);
    toplevel_notify_changed(tl);
}

static void toplevel_show_window_menu(struct wl_client *c, struct wl_resource *r,
                                      struct wl_resource *seat, uint32_t serial,
                                      int32_t x, int32_t y)
{
    (void)c; (void)r; (void)seat; (void)serial; (void)x; (void)y;
}

static void toplevel_move(struct wl_client *c, struct wl_resource *r,
                          struct wl_resource *seat, uint32_t serial)
{
    (void)c; (void)r; (void)seat; (void)serial;
}

static void toplevel_resize(struct wl_client *c, struct wl_resource *r,
                            struct wl_resource *seat, uint32_t serial,
                            uint32_t edges)
{
    (void)c; (void)r; (void)seat; (void)serial; (void)edges;
}

static void toplevel_set_size(struct wl_client *c, struct wl_resource *r,
                              int32_t w, int32_t h)
{
    (void)c; (void)r; (void)w; (void)h;
}

static void toplevel_state_req(struct wl_client *c, struct wl_resource *r)
{
    // Window state is owned by Android, answer with the current state
    (void)c;
    struct dnc_toplevel *tl = wl_resource_get_user_data(r);
    if (tl && tl->xdg && tl->xdg->configured)
        dnc_toplevel_configure(tl);
}

static void toplevel_set_fullscreen(struct wl_client *c, struct wl_resource *r,
                                    struct wl_resource *output)
{
    (void)output;
    toplevel_state_req(c, r);
}

static void toplevel_set_minimized(struct wl_client *c, struct wl_resource *r)
{
    (void)c; (void)r;
}

static const struct xdg_toplevel_interface toplevel_impl = {
    .destroy = toplevel_destroy_req,
    .set_parent = toplevel_set_parent,
    .set_title = toplevel_set_title,
    .set_app_id = toplevel_set_app_id,
    .show_window_menu = toplevel_show_window_menu,
    .move = toplevel_move,
    .resize = toplevel_resize,
    .set_max_size = toplevel_set_size,
    .set_min_size = toplevel_set_size,
    .set_maximized = toplevel_state_req,
    .unset_maximized = toplevel_state_req,
    .set_fullscreen = toplevel_set_fullscreen,
    .unset_fullscreen = toplevel_state_req,
    .set_minimized = toplevel_set_minimized,
};

static void toplevel_free(struct dnc_toplevel *tl)
{
    struct dnc_server *srv = tl->server;
    if (tl->announced && srv->callbacks.toplevel_closed)
        srv->callbacks.toplevel_closed(srv->callback_data, tl->id);

    struct dnc_popup *popup, *tmp;
    wl_list_for_each_safe(popup, tmp, &tl->popups, link) {
        wl_list_remove(&popup->link);
        wl_list_init(&popup->link);
    }
    if (tl->window) {
        srv->renderer->window_destroy(srv->renderer_state, tl->window->rwin);
        free(tl->window);
    }
    pthread_mutex_lock(&srv->stats_lock);
    wl_list_remove(&tl->link);
    pthread_mutex_unlock(&srv->stats_lock);
    free(tl->title);
    free(tl->app_id);
    free(tl);
}

static void toplevel_resource_destroy(struct wl_resource *r)
{
    struct dnc_toplevel *tl = wl_resource_get_user_data(r);
    if (!tl)
        return;
    if (tl->xdg) {
        tl->xdg->toplevel = NULL;
        tl->xdg->role_resource = NULL;
        if (tl->xdg->surface)
            tl->xdg->surface->mapped = false;
    }
    toplevel_free(tl);
}

/* xdg_popup */

static void popup_destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void popup_grab(struct wl_client *c, struct wl_resource *r,
                       struct wl_resource *seat, uint32_t serial)
{
    (void)c; (void)r; (void)seat; (void)serial;
}

static void popup_send_configure(struct dnc_popup *popup)
{
    struct dnc_xdg_surface *xdg = popup->xdg;
    if (!xdg || !xdg->role_resource || !xdg->surface)
        return;
    xdg_popup_send_configure(xdg->role_resource, popup->x, popup->y,
                             popup->width, popup->height);
    xdg->last_serial = next_serial(xdg->surface->server);
    xdg_surface_send_configure(xdg->resource, xdg->last_serial);
}

static void popup_reposition(struct wl_client *c, struct wl_resource *r,
                             struct wl_resource *positioner, uint32_t token)
{
    (void)c;
    struct dnc_popup *popup = wl_resource_get_user_data(r);
    if (!popup)
        return;
    struct dnc_positioner *p = wl_resource_get_user_data(positioner);
    positioner_place(p, &popup->x, &popup->y);
    popup->width = p->width;
    popup->height = p->height;
    xdg_popup_send_repositioned(r, token);
    popup_send_configure(popup);
}

static const struct xdg_popup_interface popup_impl = {
    .destroy = popup_destroy_req,
    .grab = popup_grab,
    .reposition = popup_reposition,
};

static void popup_resource_destroy(struct wl_resource *r)
{
    struct dnc_popup *popup = wl_resource_get_user_data(r);
    if (!popup)
        return;
    wl_list_remove(&popup->link);
    if (popup->xdg) {
        popup->xdg->popup = NULL;
        popup->xdg->role_resource = NULL;
        if (popup->xdg->surface) {
            popup->xdg->surface->mapped = false;
            struct dnc_toplevel *tl = dnc_surface_get_toplevel(popup->xdg->surface);
            if (tl)
                dnc_toplevel_schedule_repaint(tl);
        }
    }
    free(popup);
}

/* xdg_surface */

static void xdg_surface_destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    struct dnc_xdg_surface *xdg = wl_resource_get_user_data(r);
    if (xdg && xdg->role_resource) {
        wl_resource_post_error(r, XDG_SURFACE_ERROR_DEFUNCT_ROLE_OBJECT,
                               "role object still exists");
        return;
    }
    wl_resource_destroy(r);
}

static void xdg_surface_get_toplevel(struct wl_client *c, struct wl_resource *r,
                                     uint32_t id)
{
    struct dnc_xdg_surface *xdg = wl_resource_get_user_data(r);
    struct dnc_server *srv = xdg->surface->server;
    if (xdg->role_resource || (xdg->surface->role != DNC_ROLE_NONE &&
                               xdg->surface->role != DNC_ROLE_XDG_TOPLEVEL)) {
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_ROLE, "surface has a role");
        return;
    }
    struct dnc_toplevel *tl = calloc(1, sizeof(*tl));
    if (!tl) {
        wl_client_post_no_memory(c);
        return;
    }
    struct wl_resource *res = wl_resource_create(c, &xdg_toplevel_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        free(tl);
        wl_client_post_no_memory(c);
        return;
    }
    free(tl);
    tl = dnc_toplevel_create(xdg);
    if (!tl) {
        wl_resource_destroy(res);
        wl_client_post_no_memory(c);
        return;
    }
    xdg->role_resource = res;
    wl_resource_set_implementation(res, &toplevel_impl, tl,
                                   toplevel_resource_destroy);
}

struct dnc_toplevel *dnc_toplevel_create(struct dnc_xdg_surface *xdg)
{
    struct dnc_server *srv = xdg->server;
    struct dnc_toplevel *tl = calloc(1, sizeof(*tl));
    if (!tl)
        return NULL;
    tl->server = srv;
    tl->id = srv->next_toplevel_id++;
    tl->xdg = xdg;
    wl_list_init(&tl->popups);
    xdg->toplevel = tl;
    xdg->surface->role = DNC_ROLE_XDG_TOPLEVEL;
    pthread_mutex_lock(&srv->stats_lock);
    wl_list_insert(srv->toplevels.prev, &tl->link);
    pthread_mutex_unlock(&srv->stats_lock);
    return tl;
}

void dnc_toplevel_destroy(struct dnc_toplevel *tl)
{
    if (tl->xdg) {
        tl->xdg->toplevel = NULL;
        if (tl->xdg->surface)
            tl->xdg->surface->mapped = false;
    }
    toplevel_free(tl);
}

static void xdg_surface_get_popup(struct wl_client *c, struct wl_resource *r,
                                  uint32_t id, struct wl_resource *parent_res,
                                  struct wl_resource *positioner_res)
{
    struct dnc_xdg_surface *xdg = wl_resource_get_user_data(r);
    if (xdg->role_resource || (xdg->surface->role != DNC_ROLE_NONE &&
                               xdg->surface->role != DNC_ROLE_XDG_POPUP)) {
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_ROLE, "surface has a role");
        return;
    }
    if (!parent_res) {
        // Parent-less popups need another protocol (layer-shell), unsupported
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_INVALID_POPUP_PARENT,
                               "popup without parent is not supported");
        return;
    }
    struct dnc_xdg_surface *parent = wl_resource_get_user_data(parent_res);
    struct dnc_toplevel *tl = parent->surface ? dnc_surface_get_toplevel(parent->surface) : NULL;
    if (!tl) {
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_INVALID_POPUP_PARENT,
                               "popup parent has no toplevel");
        return;
    }
    struct dnc_positioner *p = wl_resource_get_user_data(positioner_res);
    if (p->width < 1 || p->height < 1) {
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_INVALID_POSITIONER,
                               "positioner has no size");
        return;
    }
    struct dnc_popup *popup = calloc(1, sizeof(*popup));
    if (!popup) {
        wl_client_post_no_memory(c);
        return;
    }
    struct wl_resource *res = wl_resource_create(c, &xdg_popup_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        free(popup);
        wl_client_post_no_memory(c);
        return;
    }
    popup->xdg = xdg;
    popup->parent = parent;
    positioner_place(p, &popup->x, &popup->y);
    popup->width = p->width;
    popup->height = p->height;
    wl_list_insert(tl->popups.prev, &popup->link);
    xdg->popup = popup;
    xdg->role_resource = res;
    xdg->surface->role = DNC_ROLE_XDG_POPUP;
    wl_resource_set_implementation(res, &popup_impl, popup, popup_resource_destroy);
}

static void xdg_surface_set_window_geometry(struct wl_client *c,
                                            struct wl_resource *r, int32_t x,
                                            int32_t y, int32_t w, int32_t h)
{
    (void)c;
    struct dnc_xdg_surface *xdg = wl_resource_get_user_data(r);
    if (w <= 0 || h <= 0) {
        wl_resource_post_error(r, XDG_SURFACE_ERROR_INVALID_SIZE, "invalid geometry");
        return;
    }
    xdg->pending_geo_x = x;
    xdg->pending_geo_y = y;
    xdg->pending_geo_w = w;
    xdg->pending_geo_h = h;
    xdg->pending_has_geometry = true;
}

static void xdg_surface_ack_configure(struct wl_client *c, struct wl_resource *r,
                                      uint32_t serial)
{
    (void)c; (void)serial;
    struct dnc_xdg_surface *xdg = wl_resource_get_user_data(r);
    xdg->configured = true;
}

static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = xdg_surface_destroy_req,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_window_geometry,
    .ack_configure = xdg_surface_ack_configure,
};

static void xdg_surface_resource_destroy(struct wl_resource *r)
{
    struct dnc_xdg_surface *xdg = wl_resource_get_user_data(r);
    // On client teardown the role object may outlive us
    if (xdg->toplevel)
        xdg->toplevel->xdg = NULL;
    if (xdg->popup)
        xdg->popup->xdg = NULL;
    struct dnc_toplevel *tl;
    wl_list_for_each(tl, &xdg->server->toplevels, link) {
        struct dnc_popup *popup;
        wl_list_for_each(popup, &tl->popups, link) {
            if (popup->parent == xdg)
                popup->parent = NULL;
        }
    }
    if (xdg->surface && xdg->surface->role_data == xdg)
        xdg->surface->role_data = NULL;
    free(xdg);
}

// Called from wl_surface.commit for surfaces with an xdg role
void dnc_xdg_surface_commit(struct dnc_xdg_surface *xdg)
{
    if (xdg->pending_has_geometry) {
        xdg->geo_x = xdg->pending_geo_x;
        xdg->geo_y = xdg->pending_geo_y;
        xdg->geo_w = xdg->pending_geo_w;
        xdg->geo_h = xdg->pending_geo_h;
        xdg->has_geometry = true;
        xdg->pending_has_geometry = false;
    }

    struct dnc_toplevel *tl = xdg->toplevel;
    if (tl) {
        struct dnc_server *srv = tl->server;
        if (!tl->announced) {
            // First commit: initial configure, then let the host know
            tl->announced = true;
            dnc_toplevel_configure(tl);
            if (srv->callbacks.toplevel_new) {
                pid_t pid = 0;
                if (xdg->x11)
                    pid = dnc_xwayland_pid(xdg->x11);
                else
                    wl_client_get_credentials(wl_resource_get_client(xdg->resource),
                                              &pid, NULL, NULL);
                srv->callbacks.toplevel_new(srv->callback_data, tl->id,
                                            tl->app_id ? tl->app_id : "",
                                            tl->title ? tl->title : "", pid);
            }
        }
        return;
    }
    if (xdg->popup && xdg->last_serial == 0)
        popup_send_configure(xdg->popup);
}

/* xdg_wm_base */

static void wm_base_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void wm_base_create_positioner(struct wl_client *c, struct wl_resource *r,
                                      uint32_t id)
{
    struct dnc_positioner *p = calloc(1, sizeof(*p));
    if (!p) {
        wl_client_post_no_memory(c);
        return;
    }
    struct wl_resource *res = wl_resource_create(c, &xdg_positioner_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        free(p);
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(res, &positioner_impl, p,
                                   positioner_resource_destroy);
}

static void wm_base_get_xdg_surface(struct wl_client *c, struct wl_resource *r,
                                    uint32_t id, struct wl_resource *surface_res)
{
    struct dnc_surface *s = dnc_surface_from_resource(surface_res);
    if (!s || s->role_data || (s->role != DNC_ROLE_NONE &&
                               s->role != DNC_ROLE_XDG_TOPLEVEL &&
                               s->role != DNC_ROLE_XDG_POPUP)) {
        wl_resource_post_error(r, XDG_WM_BASE_ERROR_ROLE, "surface has a role");
        return;
    }
    if (s->mapped) {
        wl_resource_post_error(r, XDG_SURFACE_ERROR_UNCONFIGURED_BUFFER,
                               "surface already has a buffer");
        return;
    }
    struct dnc_xdg_surface *xdg = calloc(1, sizeof(*xdg));
    if (!xdg) {
        wl_client_post_no_memory(c);
        return;
    }
    xdg->resource = wl_resource_create(c, &xdg_surface_interface,
                                       wl_resource_get_version(r), id);
    if (!xdg->resource) {
        free(xdg);
        wl_client_post_no_memory(c);
        return;
    }
    xdg->server = s->server;
    xdg->surface = s;
    s->role_data = xdg;
    wl_resource_set_implementation(xdg->resource, &xdg_surface_impl, xdg,
                                   xdg_surface_resource_destroy);
}

static void wm_base_pong(struct wl_client *c, struct wl_resource *r,
                         uint32_t serial)
{
    (void)c; (void)r; (void)serial;
}

static const struct xdg_wm_base_interface wm_base_impl = {
    .destroy = wm_base_destroy,
    .create_positioner = wm_base_create_positioner,
    .get_xdg_surface = wm_base_get_xdg_surface,
    .pong = wm_base_pong,
};

static void bind_wm_base(struct wl_client *c, void *data, uint32_t version,
                         uint32_t id)
{
    struct wl_resource *r = wl_resource_create(c, &xdg_wm_base_interface,
                                               (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &wm_base_impl, data, NULL);
}

// Dismisses every popup of a toplevel (click outside, window hidden, ...)
void dnc_toplevel_dismiss_popups(struct dnc_toplevel *tl)
{
    struct dnc_popup *popup;
    wl_list_for_each_reverse(popup, &tl->popups, link)
        popup_dismiss(popup);
}

bool dnc_xdg_shell_init(struct dnc_server *srv)
{
    return wl_global_create(srv->display, &xdg_wm_base_interface,
                            XDG_WM_BASE_VERSION, srv, bind_wm_base) != NULL;
}
