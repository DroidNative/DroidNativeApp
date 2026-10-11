/*
 * Pointer capture for games and 3D programs: zwp_pointer_constraints_v1
 * (lock or confine the pointer to a surface) and
 * zwp_relative_pointer_manager_v1 (unaccelerated movement deltas).
 * Xwayland uses both for X11 pointer grabs.
 *
 * A constraint is active while its surface belongs to the toplevel with
 * keyboard focus. While a lock is active the pointer stays where it is:
 * absolute motion from the host is ignored, relative motion is sent, and
 * the host is told (callbacks.pointer_lock) so it can switch to relative
 * input. Confinement needs nothing more: windows fill the host window.
 * Regions and cursor position hints are accepted and ignored.
 */
#include <stdlib.h>

#include "dnc_private.h"
#include "pointer-constraints-unstable-v1-server-protocol.h"
#include "relative-pointer-unstable-v1-server-protocol.h"

struct constraint {
    struct wl_resource *resource;
    struct dnc_server *server;
    struct dnc_surface *surface;         // NULL once destroyed
    bool lock;                           // locked_pointer, else confined
    bool persistent;
    bool active;
    bool defunct;                        // oneshot that was deactivated
    struct wl_list link;                 // dnc_server.constraints
    struct wl_listener surface_destroy;
};

static struct dnc_toplevel *focused_toplevel(struct dnc_server *srv)
{
    struct dnc_surface *k = srv->seat.keyboard_focus;
    return k ? dnc_surface_get_toplevel(k) : NULL;
}

static void deactivate(struct constraint *c)
{
    struct dnc_server *srv = c->server;
    if (!c->active)
        return;
    c->active = false;
    if (c->lock)
        zwp_locked_pointer_v1_send_unlocked(c->resource);
    else
        zwp_confined_pointer_v1_send_unconfined(c->resource);
    if (!c->persistent)
        c->defunct = true;
    if (srv->active_constraint == c)
        srv->active_constraint = NULL;
    if (c->lock && srv->callbacks.pointer_lock) {
        struct dnc_toplevel *tl = c->surface ? dnc_surface_get_toplevel(c->surface) : NULL;
        srv->callbacks.pointer_lock(srv->callback_data, tl ? tl->id : 0, false);
    }
}

static void activate(struct constraint *c)
{
    struct dnc_server *srv = c->server;
    struct dnc_toplevel *tl = dnc_surface_get_toplevel(c->surface);
    c->active = true;
    srv->active_constraint = c;
    // The locked surface has the pointer, wherever it was
    dnc_seat_pointer_focus_surface(srv, c->surface);
    if (c->lock)
        zwp_locked_pointer_v1_send_locked(c->resource);
    else
        zwp_confined_pointer_v1_send_confined(c->resource);
    if (c->lock && srv->callbacks.pointer_lock)
        srv->callbacks.pointer_lock(srv->callback_data, tl ? tl->id : 0, true);
}

void dnc_constraints_update(struct dnc_server *srv)
{
    if (!srv->constraints.next)
        return;
    struct dnc_toplevel *focus = focused_toplevel(srv);
    struct constraint *want = NULL, *c;
    wl_list_for_each(c, &srv->constraints, link) {
        if (!c->defunct && c->surface && focus && dnc_surface_get_toplevel(c->surface) == focus) {
            want = c;
            break;
        }
    }
    if (srv->active_constraint == want)
        return;
    if (srv->active_constraint)
        deactivate(srv->active_constraint);
    if (want)
        activate(want);
}

bool dnc_pointer_locked(struct dnc_server *srv)
{
    return srv->active_constraint && srv->active_constraint->lock;
}

/* Constraint objects */

static void constraint_destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void ignore_region(struct wl_client *c, struct wl_resource *r, struct wl_resource *region)
{
    (void)c; (void)r; (void)region;
}

static void ignore_hint(struct wl_client *c, struct wl_resource *r, wl_fixed_t x, wl_fixed_t y)
{
    (void)c; (void)r; (void)x; (void)y;
}

static const struct zwp_locked_pointer_v1_interface locked_impl = {
    .destroy = constraint_destroy_req,
    .set_cursor_position_hint = ignore_hint,
    .set_region = ignore_region,
};

static const struct zwp_confined_pointer_v1_interface confined_impl = {
    .destroy = constraint_destroy_req,
    .set_region = ignore_region,
};

static void constraint_free(struct constraint *c)
{
    struct dnc_server *srv = c->server;
    bool was_active = c->active;
    if (was_active)
        deactivate(c);
    wl_list_remove(&c->link);
    wl_list_remove(&c->surface_destroy.link);
    free(c);
    if (was_active)
        dnc_constraints_update(srv);
}

static void constraint_resource_destroy(struct wl_resource *r)
{
    constraint_free(wl_resource_get_user_data(r));
}

static void constraint_surface_destroyed(struct wl_listener *l, void *data)
{
    struct constraint *c = wl_container_of(l, c, surface_destroy);
    (void)data;
    if (c->active)
        deactivate(c);
    c->surface = NULL;
    c->defunct = true;
    wl_list_remove(&c->surface_destroy.link);
    wl_list_init(&c->surface_destroy.link);
}

static void create_constraint(struct wl_client *client, struct wl_resource *r, uint32_t id,
                              struct wl_resource *surface, uint32_t lifetime, bool lock)
{
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct dnc_surface *s = dnc_surface_from_resource(surface);
    struct constraint *existing;
    wl_list_for_each(existing, &srv->constraints, link) {
        if (existing->surface == s) {
            wl_resource_post_error(r, ZWP_POINTER_CONSTRAINTS_V1_ERROR_ALREADY_CONSTRAINED,
                                   "surface already has a pointer constraint");
            return;
        }
    }
    struct constraint *c = calloc(1, sizeof(*c));
    if (!c) {
        wl_client_post_no_memory(client);
        return;
    }
    c->resource = wl_resource_create(client, lock ? &zwp_locked_pointer_v1_interface
                                                  : &zwp_confined_pointer_v1_interface,
                                     wl_resource_get_version(r), id);
    if (!c->resource) {
        free(c);
        wl_client_post_no_memory(client);
        return;
    }
    c->server = srv;
    c->surface = s;
    c->lock = lock;
    c->persistent = lifetime == ZWP_POINTER_CONSTRAINTS_V1_LIFETIME_PERSISTENT;
    c->surface_destroy.notify = constraint_surface_destroyed;
    wl_resource_add_destroy_listener(surface, &c->surface_destroy);
    wl_list_insert(&srv->constraints, &c->link);
    if (lock)
        wl_resource_set_implementation(c->resource, &locked_impl, c, constraint_resource_destroy);
    else
        wl_resource_set_implementation(c->resource, &confined_impl, c, constraint_resource_destroy);
    dnc_constraints_update(srv);
}

static void constraints_lock(struct wl_client *c, struct wl_resource *r, uint32_t id,
                             struct wl_resource *surface, struct wl_resource *pointer,
                             struct wl_resource *region, uint32_t lifetime)
{
    (void)pointer; (void)region;
    create_constraint(c, r, id, surface, lifetime, true);
}

static void constraints_confine(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                struct wl_resource *surface, struct wl_resource *pointer,
                                struct wl_resource *region, uint32_t lifetime)
{
    (void)pointer; (void)region;
    create_constraint(c, r, id, surface, lifetime, false);
}

static const struct zwp_pointer_constraints_v1_interface constraints_impl = {
    .destroy = constraint_destroy_req,
    .lock_pointer = constraints_lock,
    .confine_pointer = constraints_confine,
};

static void bind_constraints(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct wl_resource *r = wl_resource_create(c, &zwp_pointer_constraints_v1_interface,
                                               (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &constraints_impl, data, NULL);
}

/* Relative pointer */

static void relative_destroy(struct wl_resource *r)
{
    wl_list_remove(wl_resource_get_link(r));
}

static const struct zwp_relative_pointer_v1_interface relative_impl = {
    .destroy = constraint_destroy_req,
};

static void relative_get(struct wl_client *c, struct wl_resource *r, uint32_t id,
                         struct wl_resource *pointer)
{
    (void)pointer;
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct wl_resource *res = wl_resource_create(c, &zwp_relative_pointer_v1_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(res, &relative_impl, srv, relative_destroy);
    wl_list_insert(&srv->relative_pointers, wl_resource_get_link(res));
}

static const struct zwp_relative_pointer_manager_v1_interface relative_manager_impl = {
    .destroy = constraint_destroy_req,
    .get_relative_pointer = relative_get,
};

static void bind_relative(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct wl_resource *r = wl_resource_create(c, &zwp_relative_pointer_manager_v1_interface,
                                               (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &relative_manager_impl, data, NULL);
}

void dnc_relative_motion(struct dnc_server *srv, double dx, double dy, uint32_t time_ms)
{
    struct dnc_surface *focus = srv->seat.pointer_focus;
    if (!focus || (dx == 0 && dy == 0))
        return;
    struct wl_client *client = wl_resource_get_client(focus->resource);
    uint64_t us = (uint64_t)time_ms * 1000;
    struct wl_resource *res;
    wl_resource_for_each(res, &srv->relative_pointers) {
        if (wl_resource_get_client(res) != client)
            continue;
        zwp_relative_pointer_v1_send_relative_motion(res, (uint32_t)(us >> 32), (uint32_t)us,
            wl_fixed_from_double(dx), wl_fixed_from_double(dy),
            wl_fixed_from_double(dx), wl_fixed_from_double(dy));
    }
}

bool dnc_constraints_init(struct dnc_server *srv)
{
    wl_list_init(&srv->constraints);
    wl_list_init(&srv->relative_pointers);
    return wl_global_create(srv->display, &zwp_pointer_constraints_v1_interface, 1, srv,
                            bind_constraints) &&
           wl_global_create(srv->display, &zwp_relative_pointer_manager_v1_interface, 1, srv,
                            bind_relative);
}
