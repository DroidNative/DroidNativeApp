/* wl_output: a single output describing the phone display */
#include <wayland-server-protocol.h>

#include "dnc_private.h"

#define OUTPUT_VERSION 4

static void output_release(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static const struct wl_output_interface output_impl = {
    .release = output_release,
};

static void bind_output(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct dnc_server *srv = data;
    const struct dnc_config *cfg = &srv->config;
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &output_impl, srv, NULL);

    wl_output_send_geometry(r, 0, 0, cfg->phys_width_mm, cfg->phys_height_mm,
                            WL_OUTPUT_SUBPIXEL_UNKNOWN, "Android", "DroidNative",
                            WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED,
                        cfg->display_width, cfg->display_height, cfg->refresh_mhz);
    if (version >= WL_OUTPUT_SCALE_SINCE_VERSION)
        wl_output_send_scale(r, cfg->scale);
    if (version >= WL_OUTPUT_NAME_SINCE_VERSION) {
        wl_output_send_name(r, "ANDROID-1");
        wl_output_send_description(r, "Android display");
    }
    if (version >= WL_OUTPUT_DONE_SINCE_VERSION)
        wl_output_send_done(r);
}

bool dnc_output_init(struct dnc_server *srv)
{
    return wl_global_create(srv->display, &wl_output_interface, OUTPUT_VERSION,
                            srv, bind_output) != NULL;
}
