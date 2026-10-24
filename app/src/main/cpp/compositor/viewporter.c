/* wp_viewporter: a client sets the logical size of a surface independently
 * of its buffer. Firefox needs it: it attaches its content subsurface at
 * device resolution and gives the window size as destination. Without it the
 * page showed at twice its size and taps landed in the wrong place.
 *
 * The destination (or the source size) replaces buffer size / scale in
 * dnc_surface_width/height, which drawing and input use. Cropping to a
 * source rectangle smaller than the buffer is not drawn yet: the whole
 * buffer is scaled into the destination. */
#include <wayland-server-protocol.h>

#include "dnc_private.h"
#include "viewporter-server-protocol.h"

static void viewport_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void viewport_set_source(struct wl_client *c, struct wl_resource *r,
                                wl_fixed_t x, wl_fixed_t y, wl_fixed_t w, wl_fixed_t h)
{
    (void)c;
    struct dnc_surface *s = wl_resource_get_user_data(r);
    if (!s) {
        wl_resource_post_error(r, WP_VIEWPORT_ERROR_NO_SURFACE, "surface is gone");
        return;
    }
    bool unset = x == wl_fixed_from_int(-1) && y == wl_fixed_from_int(-1) &&
                 w == wl_fixed_from_int(-1) && h == wl_fixed_from_int(-1);
    if (!unset && (x < 0 || y < 0 || w <= 0 || h <= 0)) {
        wl_resource_post_error(r, WP_VIEWPORT_ERROR_BAD_VALUE, "invalid source");
        return;
    }
    s->pending.viewport.src_set = !unset;
    s->pending.viewport.src_x = x;
    s->pending.viewport.src_y = y;
    s->pending.viewport.src_w = w;
    s->pending.viewport.src_h = h;
    s->pending.viewport.changed = true;
}

static void viewport_set_destination(struct wl_client *c, struct wl_resource *r,
                                     int32_t w, int32_t h)
{
    (void)c;
    struct dnc_surface *s = wl_resource_get_user_data(r);
    if (!s) {
        wl_resource_post_error(r, WP_VIEWPORT_ERROR_NO_SURFACE, "surface is gone");
        return;
    }
    bool unset = w == -1 && h == -1;
    if (!unset && (w <= 0 || h <= 0)) {
        wl_resource_post_error(r, WP_VIEWPORT_ERROR_BAD_VALUE, "invalid destination");
        return;
    }
    s->pending.viewport.dst_w = unset ? 0 : w;
    s->pending.viewport.dst_h = unset ? 0 : h;
    s->pending.viewport.changed = true;
}

static const struct wp_viewport_interface viewport_impl = {
    .destroy = viewport_destroy,
    .set_source = viewport_set_source,
    .set_destination = viewport_set_destination,
};

// Destroying the viewport removes its state with the next commit
static void viewport_resource_destroy(struct wl_resource *r)
{
    struct dnc_surface *s = wl_resource_get_user_data(r);
    if (!s)
        return;
    s->viewport = NULL;
    s->pending.viewport.src_set = false;
    s->pending.viewport.dst_w = s->pending.viewport.dst_h = 0;
    s->pending.viewport.changed = true;
}

static void viewporter_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void viewporter_get_viewport(struct wl_client *c, struct wl_resource *r,
                                    uint32_t id, struct wl_resource *surface)
{
    struct dnc_surface *s = dnc_surface_from_resource(surface);
    if (s->viewport) {
        wl_resource_post_error(r, WP_VIEWPORTER_ERROR_VIEWPORT_EXISTS,
                               "surface already has a viewport");
        return;
    }
    struct wl_resource *vr = wl_resource_create(c, &wp_viewport_interface,
                                                wl_resource_get_version(r), id);
    if (!vr) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(vr, &viewport_impl, s, viewport_resource_destroy);
    s->viewport = vr;
}

static const struct wp_viewporter_interface viewporter_impl = {
    .destroy = viewporter_destroy,
    .get_viewport = viewporter_get_viewport,
};

static void bind_viewporter(struct wl_client *c, void *data, uint32_t version, uint32_t id)
{
    struct wl_resource *r = wl_resource_create(c, &wp_viewporter_interface, (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &viewporter_impl, data, NULL);
}

/* Called from surface.c */

void dnc_viewport_commit(struct dnc_surface *s)
{
    if (!s->pending.viewport.changed)
        return;
    s->pending.viewport.changed = false;
    bool cropped = s->pending.viewport.src_set && !s->current_viewport.src_set;
    s->current_viewport = s->pending.viewport;
    if (cropped)
        dnc_log(s->server, DNC_LOG_INFO, "viewport source set (crop not drawn, whole buffer scaled)");
}

void dnc_viewport_surface_destroyed(struct dnc_surface *s)
{
    if (s->viewport)
        wl_resource_set_user_data(s->viewport, NULL);
}

bool dnc_viewporter_init(struct dnc_server *srv)
{
    return wl_global_create(srv->display, &wp_viewporter_interface, 1, srv, bind_viewporter);
}
