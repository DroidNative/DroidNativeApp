/*
 * zxdg_decoration_manager_v1: always answers "server side". The host window
 * (Android task) is the decoration, so clients must not draw title bars;
 * without this global libdecor/GTK clients draw their own.
 *
 * org_kde_kwin_server_decoration_manager: the only decoration protocol GTK 3
 * speaks. GTK reads its default mode when it creates a window and draws its
 * own title bar unless the mode is "server". The host sets the mode with
 * dnc_set_server_decorations: "server" while the user works in freeform or
 * desktop (DeX) windows, which have an Android title bar, "client" in
 * fullscreen tasks, where GTK's bar is the only close button.
 */
#include <stdlib.h>

#include "dnc_private.h"
#include "server-decoration-server-protocol.h"
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

/* org_kde_kwin_server_decoration */

static uint32_t kde_mode(struct dnc_server *srv)
{
    return srv->server_decorations ? ORG_KDE_KWIN_SERVER_DECORATION_MANAGER_MODE_SERVER
                                   : ORG_KDE_KWIN_SERVER_DECORATION_MANAGER_MODE_CLIENT;
}

static void kde_release(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

// The client's choice is taken as it is: GTK only asks for what it draws
static void kde_request_mode(struct wl_client *c, struct wl_resource *r, uint32_t mode)
{
    (void)c;
    if (mode > ORG_KDE_KWIN_SERVER_DECORATION_MODE_SERVER)
        return;
    org_kde_kwin_server_decoration_send_mode(r, mode);
}

static const struct org_kde_kwin_server_decoration_interface kde_impl = {
    .release = kde_release,
    .request_mode = kde_request_mode,
};

static void kde_manager_create(struct wl_client *c, struct wl_resource *r,
                               uint32_t id, struct wl_resource *surface)
{
    (void)surface;
    struct wl_resource *d = wl_resource_create(c, &org_kde_kwin_server_decoration_interface,
                                               wl_resource_get_version(r), id);
    if (!d) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(d, &kde_impl, NULL, NULL);
    struct dnc_server *srv = wl_resource_get_user_data(r);
    org_kde_kwin_server_decoration_send_mode(d, kde_mode(srv));
}

static const struct org_kde_kwin_server_decoration_manager_interface kde_manager_impl = {
    .create = kde_manager_create,
};

static void kde_manager_resource_destroy(struct wl_resource *r)
{
    wl_list_remove(wl_resource_get_link(r));
}

static void bind_kde_manager(struct wl_client *c, void *data, uint32_t version,
                             uint32_t id)
{
    struct dnc_server *srv = data;
    struct wl_resource *r = wl_resource_create(
        c, &org_kde_kwin_server_decoration_manager_interface, (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &kde_manager_impl, srv, kde_manager_resource_destroy);
    wl_list_insert(&srv->kde_decoration_managers, wl_resource_get_link(r));
    org_kde_kwin_server_decoration_manager_send_default_mode(r, kde_mode(srv));
}

// Windows created from now on follow the new mode; open ones keep theirs
void dnc_decoration_set_server(struct dnc_server *srv, bool server)
{
    if (srv->server_decorations == server)
        return;
    srv->server_decorations = server;
    struct wl_resource *r;
    wl_resource_for_each(r, &srv->kde_decoration_managers)
        org_kde_kwin_server_decoration_manager_send_default_mode(r, kde_mode(srv));
}

bool dnc_decoration_init(struct dnc_server *srv)
{
    wl_list_init(&srv->kde_decoration_managers);
    return wl_global_create(srv->display, &zxdg_decoration_manager_v1_interface,
                            DECORATION_VERSION, srv, bind_manager) != NULL &&
           wl_global_create(srv->display, &org_kde_kwin_server_decoration_manager_interface,
                            1, srv, bind_kde_manager) != NULL;
}
