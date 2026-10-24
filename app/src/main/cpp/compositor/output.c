/* wl_output: a single output describing the phone display, or a client's window */
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

static void output_destroy(struct wl_resource *r)
{
    wl_list_remove(wl_resource_get_link(r));
}

static void send_mode(struct wl_resource *r, const struct dnc_config *cfg, int32_t w, int32_t h)
{
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, w, h,
                        cfg->refresh_mhz);
    if (wl_resource_get_version(r) >= WL_OUTPUT_SCALE_SINCE_VERSION)
        wl_output_send_scale(r, cfg->scale);
    if (wl_resource_get_version(r) >= WL_OUTPUT_DONE_SINCE_VERSION)
        wl_output_send_done(r);
}

static void bind_output(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct dnc_server *srv = data;
    const struct dnc_config *cfg = &srv->config;
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &output_impl, srv, output_destroy);
    wl_list_insert(&srv->outputs, wl_resource_get_link(r));

    // Landscape, as games expect: SDL2 reads the mode only once, before any
    // window exists, and sizes exclusive fullscreen by it
    bool turn = cfg->display_height > cfg->display_width;
    wl_output_send_geometry(r, 0, 0, turn ? cfg->phys_height_mm : cfg->phys_width_mm,
                            turn ? cfg->phys_width_mm : cfg->phys_height_mm,
                            WL_OUTPUT_SUBPIXEL_UNKNOWN, "Android", "DroidNative",
                            WL_OUTPUT_TRANSFORM_NORMAL);
    if (version >= WL_OUTPUT_NAME_SINCE_VERSION) {
        wl_output_send_name(r, "ANDROID-1");
        wl_output_send_description(r, "Android display");
    }
    // A client that already has a window sees that window's size
    int32_t w = turn ? cfg->display_height : cfg->display_width;
    int32_t h = turn ? cfg->display_width : cfg->display_height;
    struct dnc_toplevel *tl;
    wl_list_for_each(tl, &srv->toplevels, link) {
        if (tl->window && tl->xdg && !tl->xdg->x11 && tl->xdg->surface &&
            wl_resource_get_client(tl->xdg->surface->resource) == c) {
            w = tl->window->width;
            h = tl->window->height;
        }
    }
    send_mode(r, cfg, w, h);

    // Surfaces that entered before this output was bound enter it too
    struct dnc_surface *s;
    wl_list_for_each(s, &srv->surfaces, link) {
        if (s->entered && wl_resource_get_client(s->resource) == c)
            wl_surface_send_enter(s->resource, r);
    }
}

void dnc_output_enter_surface(struct dnc_server *srv, struct dnc_surface *s)
{
    struct wl_client *c = wl_resource_get_client(s->resource);
    struct wl_resource *r;
    s->entered = true;
    wl_resource_for_each(r, &srv->outputs) {
        if (wl_resource_get_client(r) == c)
            wl_surface_send_enter(s->resource, r);
    }
}

/* Each Android window is the client's whole screen: its outputs report the
 * window's size (pixels), so fullscreen requests (SDL picks the output mode)
 * match the window. Xwayland keeps the display size: it has many windows. */
void dnc_output_client_size(struct dnc_server *srv, struct wl_client *client,
                            int32_t width, int32_t height)
{
    struct wl_resource *r;
    wl_resource_for_each(r, &srv->outputs) {
        if (wl_resource_get_client(r) == client)
            send_mode(r, &srv->config, width, height);
    }
}

bool dnc_output_init(struct dnc_server *srv)
{
    return wl_global_create(srv->display, &wl_output_interface, OUTPUT_VERSION,
                            srv, bind_output) != NULL;
}
