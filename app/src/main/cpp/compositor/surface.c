/* wl_compositor, wl_surface, wl_region, wl_subcompositor */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/dma-buf.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <wayland-server-protocol.h>

#include "dnc_private.h"

#define COMPOSITOR_VERSION 6
#define SUBCOMPOSITOR_VERSION 1

#ifndef DRM_FORMAT_ARGB8888
#define DRM_FORMAT_ARGB8888 0x34325241
#define DRM_FORMAT_XRGB8888 0x34325258
#endif

/* Regions (content is not used: input/opaque regions are ignored) */

static void region_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void region_add(struct wl_client *c, struct wl_resource *r,
                       int32_t x, int32_t y, int32_t w, int32_t h)
{
    (void)c; (void)r; (void)x; (void)y; (void)w; (void)h;
}

static const struct wl_region_interface region_impl = {
    .destroy = region_destroy,
    .add = region_add,
    .subtract = region_add,
};

/* Surface helpers */

static const struct wl_surface_interface surface_impl;

struct dnc_surface *dnc_surface_from_resource(struct wl_resource *resource)
{
    if (!resource || !wl_resource_instance_of(resource, &wl_surface_interface,
                                              &surface_impl))
        return NULL;
    return wl_resource_get_user_data(resource);
}

int32_t dnc_surface_width(const struct dnc_surface *s)
{
    return s->scale > 0 ? s->buffer_width / s->scale : s->buffer_width;
}

int32_t dnc_surface_height(const struct dnc_surface *s)
{
    return s->scale > 0 ? s->buffer_height / s->scale : s->buffer_height;
}

struct dnc_toplevel *dnc_surface_get_toplevel(struct dnc_surface *s)
{
    for (int depth = 0; s && depth < 32; depth++) {
        if (s->role == DNC_ROLE_SUBSURFACE) {
            struct dnc_subsurface *sub = s->role_data;
            s = sub ? sub->parent : NULL;
        } else if (s->role == DNC_ROLE_XDG_TOPLEVEL || s->role == DNC_ROLE_XDG_POPUP) {
            struct dnc_xdg_surface *xdg = s->role_data;
            if (!xdg)
                return NULL;
            if (xdg->toplevel)
                return xdg->toplevel;
            if (!xdg->popup || !xdg->popup->parent)
                return NULL;
            s = xdg->popup->parent->surface;
        } else {
            return NULL;
        }
    }
    return NULL;
}

void dnc_surface_send_frame_done(struct dnc_surface *s, uint32_t time_ms)
{
    struct wl_resource *cb, *tmp;
    wl_resource_for_each_safe(cb, tmp, &s->frame_callbacks) {
        wl_callback_send_done(cb, time_ms);
        wl_resource_destroy(cb);
    }
}

static void pending_buffer_destroyed(struct wl_listener *l, void *data)
{
    struct dnc_surface *s = wl_container_of(l, s, pending.buffer_destroy);
    (void)data;
    s->pending.buffer = NULL;
    wl_list_remove(&s->pending.buffer_destroy.link);
    wl_list_init(&s->pending.buffer_destroy.link);
}

static void set_pending_buffer(struct dnc_surface *s, struct wl_resource *buffer)
{
    wl_list_remove(&s->pending.buffer_destroy.link);
    wl_list_init(&s->pending.buffer_destroy.link);
    s->pending.buffer = buffer;
    if (buffer)
        wl_resource_add_destroy_listener(buffer, &s->pending.buffer_destroy);
}

/* Buffer import: copy the client buffer into the surface texture */

// Samples an 8x8 grid and counts pixels that are not black. Cheap proof
// for the device tests that real content arrived.
static uint32_t count_nonblack(const uint8_t *data, int32_t w, int32_t h,
                               int32_t stride)
{
    uint32_t n = 0;
    for (int gy = 0; gy < 8; gy++) {
        for (int gx = 0; gx < 8; gx++) {
            int32_t x = (int32_t)((gx * 2 + 1) * (int64_t)w / 16);
            int32_t y = (int32_t)((gy * 2 + 1) * (int64_t)h / 16);
            const uint8_t *p = data + (size_t)y * (size_t)stride + (size_t)x * 4;
            if ((p[0] | p[1] | p[2]) > 8)
                n++;
        }
    }
    return n;
}

static void record_commit(struct dnc_surface *s, enum dnc_buffer_kind kind,
                          int32_t w, int32_t h, uint32_t format,
                          uint32_t nonblack, bool map_failed)
{
    struct dnc_toplevel *tl = dnc_surface_get_toplevel(s);
    if (!tl)
        return;
    struct dnc_server *srv = s->server;
    bool is_root = tl->xdg && tl->xdg->surface == s;
    pthread_mutex_lock(&srv->stats_lock);
    if (map_failed) {
        tl->stats.dmabuf_map_failures++;
    } else {
        tl->stats.commits++;
        if (kind == DNC_BUF_SHM)
            tl->stats.shm_commits++;
        else if (kind == DNC_BUF_DMABUF)
            tl->stats.dmabuf_commits++;
        if (is_root) {
            tl->stats.last_kind = kind;
            tl->stats.last_width = w;
            tl->stats.last_height = h;
            tl->stats.last_format = format;
            tl->stats.last_nonblack = nonblack;
        }
    }
    pthread_mutex_unlock(&srv->stats_lock);
}

static uint32_t shm_to_drm_format(uint32_t f)
{
    if (f == WL_SHM_FORMAT_ARGB8888)
        return DRM_FORMAT_ARGB8888;
    if (f == WL_SHM_FORMAT_XRGB8888)
        return DRM_FORMAT_XRGB8888;
    return f;   // all other wl_shm formats equal their DRM fourcc
}

static bool import_shm(struct dnc_surface *s, struct wl_shm_buffer *shm)
{
    struct dnc_server *srv = s->server;
    int32_t w = wl_shm_buffer_get_width(shm);
    int32_t h = wl_shm_buffer_get_height(shm);
    int32_t stride = wl_shm_buffer_get_stride(shm);
    uint32_t format = shm_to_drm_format(wl_shm_buffer_get_format(shm));

    wl_shm_buffer_begin_access(shm);
    const void *data = wl_shm_buffer_get_data(shm);
    bool ok = srv->renderer->upload(srv->renderer_state, &s->texture, data,
                                    w, h, stride, format);
    uint32_t nonblack = count_nonblack(data, w, h, stride);
    wl_shm_buffer_end_access(shm);

    if (ok) {
        s->buffer_width = w;
        s->buffer_height = h;
        record_commit(s, DNC_BUF_SHM, w, h, format, nonblack, false);
    }
    return ok;
}

static void dmabuf_sync(int fd, uint64_t flags)
{
    struct dma_buf_sync sync = { .flags = flags };
    while (ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) < 0 && (errno == EINTR || errno == EAGAIN))
        ;
}

static bool import_dmabuf(struct dnc_surface *s, struct dnc_dmabuf *buf)
{
    struct dnc_server *srv = s->server;
    if (!buf->map) {
        buf->map_size = (size_t)buf->offset + (size_t)buf->stride * (size_t)buf->height;
        buf->map = mmap(NULL, buf->map_size, PROT_READ, MAP_SHARED, buf->fd, 0);
        if (buf->map == MAP_FAILED)
            dnc_log(srv, DNC_LOG_WARN, "dmabuf mmap failed: %s", strerror(errno));
    }
    if (buf->map == MAP_FAILED) {
        record_commit(s, DNC_BUF_DMABUF, buf->width, buf->height, buf->format, 0, true);
        return false;
    }

    const uint8_t *data = (const uint8_t *)buf->map + buf->offset;
    dmabuf_sync(buf->fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ);
    bool ok = srv->renderer->upload(srv->renderer_state, &s->texture, data,
                                    buf->width, buf->height,
                                    (int32_t)buf->stride, buf->format);
    uint32_t nonblack = count_nonblack(data, buf->width, buf->height,
                                       (int32_t)buf->stride);
    dmabuf_sync(buf->fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);

    if (ok) {
        s->buffer_width = buf->width;
        s->buffer_height = buf->height;
        record_commit(s, DNC_BUF_DMABUF, buf->width, buf->height, buf->format,
                      nonblack, false);
    }
    return ok;
}

static void apply_buffer(struct dnc_surface *s, struct wl_resource *buffer)
{
    if (!buffer) {
        s->mapped = false;
        return;
    }
    struct wl_shm_buffer *shm = wl_shm_buffer_get(buffer);
    struct dnc_dmabuf *dmabuf = shm ? NULL : dnc_dmabuf_from_resource(buffer);
    bool ok = false;
    if (shm)
        ok = import_shm(s, shm);
    else if (dmabuf)
        ok = import_dmabuf(s, dmabuf);
    else
        dnc_log(s->server, DNC_LOG_WARN, "unsupported buffer type");
    if (ok)
        s->mapped = true;
    // Contents are copied, the client may reuse the buffer right away
    wl_buffer_send_release(buffer);
}

/* wl_surface requests */

static void surface_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void surface_attach(struct wl_client *c, struct wl_resource *r,
                           struct wl_resource *buffer, int32_t x, int32_t y)
{
    (void)c;
    struct dnc_surface *s = wl_resource_get_user_data(r);
    set_pending_buffer(s, buffer);
    s->pending.attached = true;
    if (wl_resource_get_version(r) < WL_SURFACE_OFFSET_SINCE_VERSION) {
        s->pending.dx = x;
        s->pending.dy = y;
    }
}

static void surface_damage(struct wl_client *c, struct wl_resource *r,
                           int32_t x, int32_t y, int32_t w, int32_t h)
{
    // Every commit uploads the whole buffer, damage is not tracked
    (void)c; (void)r; (void)x; (void)y; (void)w; (void)h;
}

static void callback_resource_destroy(struct wl_resource *cb)
{
    wl_list_remove(wl_resource_get_link(cb));
}

static void surface_frame(struct wl_client *c, struct wl_resource *r,
                          uint32_t id)
{
    struct dnc_surface *s = wl_resource_get_user_data(r);
    struct wl_resource *cb = wl_resource_create(c, &wl_callback_interface, 1, id);
    if (!cb) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(cb, NULL, NULL, callback_resource_destroy);
    wl_list_insert(s->pending.frame_callbacks.prev, wl_resource_get_link(cb));
}

static void surface_set_region(struct wl_client *c, struct wl_resource *r,
                               struct wl_resource *region)
{
    (void)c; (void)r; (void)region;
}

static void surface_commit(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    struct dnc_surface *s = wl_resource_get_user_data(r);

    if (s->pending.scale > 0)
        s->scale = s->pending.scale;

    if (s->pending.attached) {
        apply_buffer(s, s->pending.buffer);
        set_pending_buffer(s, NULL);
        s->pending.attached = false;
    }

    wl_list_insert_list(s->frame_callbacks.prev, &s->pending.frame_callbacks);
    wl_list_init(&s->pending.frame_callbacks);

    if (s->role == DNC_ROLE_SUBSURFACE) {
        struct dnc_subsurface *sub = s->role_data;
        if (sub) {
            sub->x = sub->pending_x;
            sub->y = sub->pending_y;
        }
    } else if (s->role == DNC_ROLE_XDG_TOPLEVEL || s->role == DNC_ROLE_XDG_POPUP) {
        if (s->role_data)
            dnc_xdg_surface_commit(s->role_data);
    }

    struct dnc_toplevel *tl = dnc_surface_get_toplevel(s);
    if (tl)
        dnc_toplevel_schedule_repaint(tl);
    dnc_arm_hidden_frame_timer(s->server);
}

static void surface_set_buffer_transform(struct wl_client *c,
                                         struct wl_resource *r, int32_t t)
{
    (void)c; (void)r; (void)t;
}

static void surface_set_buffer_scale(struct wl_client *c, struct wl_resource *r,
                                     int32_t scale)
{
    (void)c;
    if (scale < 1) {
        wl_resource_post_error(r, WL_SURFACE_ERROR_INVALID_SCALE,
                               "scale must be >= 1");
        return;
    }
    struct dnc_surface *s = wl_resource_get_user_data(r);
    s->pending.scale = scale;
}

static void surface_offset(struct wl_client *c, struct wl_resource *r,
                           int32_t x, int32_t y)
{
    (void)c;
    struct dnc_surface *s = wl_resource_get_user_data(r);
    s->pending.dx = x;
    s->pending.dy = y;
}

static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_region,
    .set_input_region = surface_set_region,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_buffer_transform,
    .set_buffer_scale = surface_set_buffer_scale,
    .damage_buffer = surface_damage,
    .offset = surface_offset,
};

static void subsurface_unlink(struct dnc_subsurface *sub);

static void surface_resource_destroy(struct wl_resource *r)
{
    struct dnc_surface *s = wl_resource_get_user_data(r);
    struct dnc_server *srv = s->server;

    dnc_seat_surface_destroyed(srv, s);

    // Children lose their parent but stay alive until the client drops them
    struct dnc_subsurface *sub, *tmp;
    wl_list_for_each_safe(sub, tmp, &s->subsurfaces, link)
        subsurface_unlink(sub);

    if (s->role == DNC_ROLE_SUBSURFACE && s->role_data) {
        struct dnc_subsurface *own = s->role_data;
        subsurface_unlink(own);
        own->surface = NULL;
    } else if ((s->role == DNC_ROLE_XDG_TOPLEVEL || s->role == DNC_ROLE_XDG_POPUP) &&
               s->role_data) {
        struct dnc_xdg_surface *xdg = s->role_data;
        xdg->surface = NULL;
    }

    struct wl_resource *cb, *cbtmp;
    wl_resource_for_each_safe(cb, cbtmp, &s->pending.frame_callbacks)
        wl_resource_destroy(cb);
    wl_resource_for_each_safe(cb, cbtmp, &s->frame_callbacks)
        wl_resource_destroy(cb);

    set_pending_buffer(s, NULL);
    if (s->texture)
        srv->renderer->texture_destroy(srv->renderer_state, s->texture);
    wl_list_remove(&s->link);
    free(s);
}

/* wl_compositor */

static void compositor_create_surface(struct wl_client *c, struct wl_resource *r,
                                      uint32_t id)
{
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct dnc_surface *s = calloc(1, sizeof(*s));
    if (!s) {
        wl_client_post_no_memory(c);
        return;
    }
    s->resource = wl_resource_create(c, &wl_surface_interface,
                                     wl_resource_get_version(r), id);
    if (!s->resource) {
        free(s);
        wl_client_post_no_memory(c);
        return;
    }
    s->server = srv;
    s->scale = 1;
    wl_list_init(&s->pending.frame_callbacks);
    wl_list_init(&s->frame_callbacks);
    wl_list_init(&s->subsurfaces);
    wl_list_init(&s->pending.buffer_destroy.link);
    s->pending.buffer_destroy.notify = pending_buffer_destroyed;
    wl_resource_set_implementation(s->resource, &surface_impl, s,
                                   surface_resource_destroy);
    wl_list_insert(&srv->surfaces, &s->link);
}

static void compositor_create_region(struct wl_client *c, struct wl_resource *r,
                                     uint32_t id)
{
    struct wl_resource *region = wl_resource_create(c, &wl_region_interface, 1, id);
    if (!region) {
        wl_client_post_no_memory(c);
        return;
    }
    (void)r;
    wl_resource_set_implementation(region, &region_impl, NULL, NULL);
}

static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
};

static void bind_compositor(struct wl_client *c, void *data, uint32_t version,
                            uint32_t id)
{
    struct wl_resource *r = wl_resource_create(c, &wl_compositor_interface,
                                               (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &compositor_impl, data, NULL);
}

/* Subsurfaces (always treated as desynchronised) */

static void subsurface_unlink(struct dnc_subsurface *sub)
{
    if (sub->parent) {
        wl_list_remove(&sub->link);
        wl_list_init(&sub->link);
        sub->parent = NULL;
    }
}

static void subsurface_destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void subsurface_set_position(struct wl_client *c, struct wl_resource *r,
                                    int32_t x, int32_t y)
{
    (void)c;
    struct dnc_subsurface *sub = wl_resource_get_user_data(r);
    if (!sub)
        return;
    sub->pending_x = x;
    sub->pending_y = y;
    // Applied on the parent's commit in the protocol; applying it on the
    // next commit of either surface is close enough for desync mode.
    sub->x = x;
    sub->y = y;
}

static void subsurface_restack(struct wl_client *c, struct wl_resource *r,
                               struct wl_resource *sibling)
{
    (void)c; (void)r; (void)sibling;
}

static void subsurface_set_sync(struct wl_client *c, struct wl_resource *r)
{
    (void)c; (void)r;
}

static const struct wl_subsurface_interface subsurface_impl = {
    .destroy = subsurface_destroy_req,
    .set_position = subsurface_set_position,
    .place_above = subsurface_restack,
    .place_below = subsurface_restack,
    .set_sync = subsurface_set_sync,
    .set_desync = subsurface_set_sync,
};

static void subsurface_resource_destroy(struct wl_resource *r)
{
    struct dnc_subsurface *sub = wl_resource_get_user_data(r);
    if (!sub)
        return;
    subsurface_unlink(sub);
    if (sub->surface) {
        sub->surface->role_data = NULL;
        sub->surface->mapped = false;
    }
    free(sub);
}

static void subcompositor_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void subcompositor_get_subsurface(struct wl_client *c,
                                         struct wl_resource *r, uint32_t id,
                                         struct wl_resource *surface_res,
                                         struct wl_resource *parent_res)
{
    struct dnc_surface *s = wl_resource_get_user_data(surface_res);
    struct dnc_surface *parent = wl_resource_get_user_data(parent_res);
    if (s == parent || (s->role != DNC_ROLE_NONE && s->role != DNC_ROLE_SUBSURFACE) ||
        s->role_data) {
        wl_resource_post_error(r, WL_SUBCOMPOSITOR_ERROR_BAD_SURFACE,
                               "surface already has a role");
        return;
    }
    struct dnc_subsurface *sub = calloc(1, sizeof(*sub));
    if (!sub) {
        wl_client_post_no_memory(c);
        return;
    }
    sub->resource = wl_resource_create(c, &wl_subsurface_interface, 1, id);
    if (!sub->resource) {
        free(sub);
        wl_client_post_no_memory(c);
        return;
    }
    sub->surface = s;
    sub->parent = parent;
    wl_list_insert(&parent->subsurfaces, &sub->link);
    s->role = DNC_ROLE_SUBSURFACE;
    s->role_data = sub;
    wl_resource_set_implementation(sub->resource, &subsurface_impl, sub,
                                   subsurface_resource_destroy);
}

static const struct wl_subcompositor_interface subcompositor_impl = {
    .destroy = subcompositor_destroy,
    .get_subsurface = subcompositor_get_subsurface,
};

static void bind_subcompositor(struct wl_client *c, void *data, uint32_t version,
                               uint32_t id)
{
    struct wl_resource *r = wl_resource_create(c, &wl_subcompositor_interface,
                                               (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &subcompositor_impl, data, NULL);
}

bool dnc_compositor_init(struct dnc_server *srv)
{
    return wl_global_create(srv->display, &wl_compositor_interface,
                            COMPOSITOR_VERSION, srv, bind_compositor) &&
           wl_global_create(srv->display, &wl_subcompositor_interface,
                            SUBCOMPOSITOR_VERSION, srv, bind_subcompositor);
}
