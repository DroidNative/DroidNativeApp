/*
 * zxdg_decoration_manager_v1: always answers "server side". The host window
 * (Android task) is the decoration, so clients must not draw title bars;
 * without this global libdecor/GTK clients draw their own.
 */
#include <stdlib.h>

#include "dnc_private.h"
#include "xdg-decoration-unstable-v1-server-protocol.h"

#define DECORATION_VERSION 1

struct decoration {
    struct wl_resource *resource;
    struct dnc_toplevel *toplevel;          // NULL once the toplevel is gone
    struct wl_listener toplevel_destroy;
};

static void decoration_send(struct decoration *deco)
{
    zxdg_toplevel_decoration_v1_send_configure(
        deco->resource, ZXDG_TOPLEVEL_DECORATION_V1_MODE_SERVER_SIDE);
    // The mode takes effect with the next xdg_surface.configure
    if (deco->toplevel && deco->toplevel->announced)
        dnc_toplevel_configure(deco->toplevel);
}

static void decoration_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void decoration_set_mode(struct wl_client *c, struct wl_resource *r,
                                uint32_t mode)
{
    (void)c; (void)mode;
    decoration_send(wl_resource_get_user_data(r));
}

static void decoration_unset_mode(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    decoration_send(wl_resource_get_user_data(r));
}

static const struct zxdg_toplevel_decoration_v1_interface decoration_impl = {
    .destroy = decoration_destroy,
    .set_mode = decoration_set_mode,
    .unset_mode = decoration_unset_mode,
};

static void toplevel_destroyed(struct wl_listener *l, void *data)
{
    struct decoration *deco = wl_container_of(l, deco, toplevel_destroy);
    (void)data;
    deco->toplevel = NULL;
    wl_list_remove(&deco->toplevel_destroy.link);
    wl_list_init(&deco->toplevel_destroy.link);
}

static void decoration_resource_destroy(struct wl_resource *r)
{
    struct decoration *deco = wl_resource_get_user_data(r);
    wl_list_remove(&deco->toplevel_destroy.link);
    free(deco);
}

static void manager_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void manager_get_toplevel_decoration(struct wl_client *c,
                                            struct wl_resource *r, uint32_t id,
                                            struct wl_resource *toplevel)
{
    struct decoration *deco = calloc(1, sizeof(*deco));
    if (!deco) {
        wl_client_post_no_memory(c);
        return;
    }
    deco->resource = wl_resource_create(c, &zxdg_toplevel_decoration_v1_interface,
                                        wl_resource_get_version(r), id);
    if (!deco->resource) {
        free(deco);
        wl_client_post_no_memory(c);
        return;
    }
    deco->toplevel = wl_resource_get_user_data(toplevel);
    deco->toplevel_destroy.notify = toplevel_destroyed;
    wl_resource_add_destroy_listener(toplevel, &deco->toplevel_destroy);
    wl_resource_set_implementation(deco->resource, &decoration_impl, deco,
                                   decoration_resource_destroy);
    decoration_send(deco);
}

static const struct zxdg_decoration_manager_v1_interface manager_impl = {
    .destroy = manager_destroy,
    .get_toplevel_decoration = manager_get_toplevel_decoration,
};

static void bind_manager(struct wl_client *c, void *data, uint32_t version,
                         uint32_t id)
{
    struct wl_resource *r = wl_resource_create(
        c, &zxdg_decoration_manager_v1_interface, (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &manager_impl, data, NULL);
}

bool dnc_decoration_init(struct dnc_server *srv)
{
    return wl_global_create(srv->display, &zxdg_decoration_manager_v1_interface,
                            DECORATION_VERSION, srv, bind_manager) != NULL;
}
