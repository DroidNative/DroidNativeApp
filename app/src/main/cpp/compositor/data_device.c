/*
 * wl_data_device_manager stub. Toolkits expect the global to exist; clipboard
 * and drag and drop are not implemented yet (offers are never sent).
 */
#include <stdlib.h>
#include <unistd.h>
#include <wayland-server-protocol.h>

#include "dnc_private.h"

#define DDM_VERSION 3

static void destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void source_offer(struct wl_client *c, struct wl_resource *r, const char *mime)
{
    (void)c; (void)r; (void)mime;
}

static void source_set_actions(struct wl_client *c, struct wl_resource *r, uint32_t a)
{
    (void)c; (void)r; (void)a;
}

static const struct wl_data_source_interface source_impl = {
    .offer = source_offer,
    .destroy = destroy_req,
    .set_actions = source_set_actions,
};

static void device_start_drag(struct wl_client *c, struct wl_resource *r,
                              struct wl_resource *source, struct wl_resource *origin,
                              struct wl_resource *icon, uint32_t serial)
{
    (void)c; (void)r; (void)origin; (void)icon; (void)serial;
    if (source)
        wl_data_source_send_cancelled(source);
}

static void device_set_selection(struct wl_client *c, struct wl_resource *r,
                                 struct wl_resource *source, uint32_t serial)
{
    (void)c; (void)r; (void)source; (void)serial;
}

static const struct wl_data_device_interface device_impl = {
    .start_drag = device_start_drag,
    .set_selection = device_set_selection,
    .release = destroy_req,
};

static void manager_create_source(struct wl_client *c, struct wl_resource *r, uint32_t id)
{
    struct wl_resource *res = wl_resource_create(c, &wl_data_source_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(res, &source_impl, NULL, NULL);
}

static void manager_get_device(struct wl_client *c, struct wl_resource *r, uint32_t id,
                               struct wl_resource *seat)
{
    (void)seat;
    struct wl_resource *res = wl_resource_create(c, &wl_data_device_interface,
                                                 wl_resource_get_version(r), id);
    if (!res) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(res, &device_impl, NULL, NULL);
}

static const struct wl_data_device_manager_interface manager_impl = {
    .create_data_source = manager_create_source,
    .get_data_device = manager_get_device,
};

static void bind_manager(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct wl_resource *r = wl_resource_create(c, &wl_data_device_manager_interface,
                                               (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &manager_impl, data, NULL);
}

bool dnc_data_device_init(struct dnc_server *srv)
{
    return wl_global_create(srv->display, &wl_data_device_manager_interface,
                            DDM_VERSION, srv, bind_manager) != NULL;
}
