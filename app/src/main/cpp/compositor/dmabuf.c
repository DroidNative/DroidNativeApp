/*
 * zwp_linux_dmabuf_v1, version 3: single-plane LINEAR buffers only.
 *
 * Version 3 has no feedback/main_device, which keeps Mesa's Wayland WSI from
 * comparing DRM devices (Turnip on KGSL has none). Buffers are mmap'ed and
 * copied on commit (see surface.c, import_dmabuf).
 */
#define _GNU_SOURCE
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>
#include <wayland-server-protocol.h>

#include "dnc_private.h"
#include "linux-dmabuf-unstable-v1-server-protocol.h"

#define DMABUF_VERSION 3

#define DRM_FORMAT_MOD_LINEAR 0ull
#define DRM_FORMAT_MOD_INVALID 0x00ffffffffffffffull

static const uint32_t supported_formats[] = {
    0x34325241, // ARGB8888
    0x34325258, // XRGB8888
    0x34324241, // ABGR8888
    0x34324258, // XBGR8888
};

static bool format_supported(uint32_t format)
{
    for (size_t i = 0; i < DNC_ARRAY_LEN(supported_formats); i++) {
        if (supported_formats[i] == format)
            return true;
    }
    return false;
}

/* wl_buffer backed by a dmabuf */

static void buffer_destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static const struct wl_buffer_interface dmabuf_buffer_impl = {
    .destroy = buffer_destroy_req,
};

struct dnc_dmabuf *dnc_dmabuf_from_resource(struct wl_resource *resource)
{
    if (!wl_resource_instance_of(resource, &wl_buffer_interface, &dmabuf_buffer_impl))
        return NULL;
    return wl_resource_get_user_data(resource);
}

static void dmabuf_free(struct dnc_dmabuf *buf)
{
    if (buf->map && buf->map != MAP_FAILED)
        munmap(buf->map, buf->map_size);
    if (buf->fd >= 0)
        close(buf->fd);
    free(buf);
}

static void buffer_resource_destroy(struct wl_resource *r)
{
    dmabuf_free(wl_resource_get_user_data(r));
}

/* Params */

struct dnc_params {
    struct wl_resource *resource;
    struct dnc_dmabuf *buf;     // NULL once used
    bool plane0_set;
};

static void params_destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void params_add(struct wl_client *c, struct wl_resource *r, int32_t fd,
                       uint32_t plane_idx, uint32_t offset, uint32_t stride,
                       uint32_t mod_hi, uint32_t mod_lo)
{
    (void)c;
    struct dnc_params *p = wl_resource_get_user_data(r);
    uint64_t modifier = ((uint64_t)mod_hi << 32) | mod_lo;
    if (!p->buf) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED,
                               "params already used");
        close(fd);
        return;
    }
    if (plane_idx != 0) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX,
                               "only single-plane buffers are supported");
        close(fd);
        return;
    }
    if (p->plane0_set) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_SET,
                               "plane 0 already set");
        close(fd);
        return;
    }
    if (modifier != DRM_FORMAT_MOD_LINEAR && modifier != DRM_FORMAT_MOD_INVALID) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT,
                               "only LINEAR is supported");
        close(fd);
        return;
    }
    p->buf->fd = fd;
    p->buf->offset = offset;
    p->buf->stride = stride;
    p->plane0_set = true;
}

// Returns the buffer on success, NULL after posting/sending the failure
static struct dnc_dmabuf *params_validate(struct wl_resource *r,
                                          struct dnc_params *p, int32_t w,
                                          int32_t h, uint32_t format)
{
    if (!p->buf) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_ALREADY_USED,
                               "params already used");
        return NULL;
    }
    struct dnc_dmabuf *buf = p->buf;
    p->buf = NULL;
    if (!p->plane0_set) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INCOMPLETE,
                               "no plane set");
        dmabuf_free(buf);
        return NULL;
    }
    if (w < 1 || h < 1) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_DIMENSIONS,
                               "invalid size");
        dmabuf_free(buf);
        return NULL;
    }
    if (!format_supported(format) || buf->stride < (uint32_t)w * 4) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_FORMAT,
                               "unsupported format or stride");
        dmabuf_free(buf);
        return NULL;
    }
    off_t size = lseek(buf->fd, 0, SEEK_END);
    if (size >= 0 && (uint64_t)buf->offset + (uint64_t)buf->stride * (uint64_t)h >
                         (uint64_t)size) {
        wl_resource_post_error(r, ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_OUT_OF_BOUNDS,
                               "buffer too small");
        dmabuf_free(buf);
        return NULL;
    }
    buf->width = w;
    buf->height = h;
    buf->format = format;
    return buf;
}

static struct wl_resource *buffer_create(struct wl_client *c,
                                         struct dnc_dmabuf *buf, uint32_t id)
{
    struct wl_resource *res = wl_resource_create(c, &wl_buffer_interface, 1, id);
    if (!res) {
        dmabuf_free(buf);
        wl_client_post_no_memory(c);
        return NULL;
    }
    buf->resource = res;
    wl_resource_set_implementation(res, &dmabuf_buffer_impl, buf,
                                   buffer_resource_destroy);
    return res;
}

static void params_create(struct wl_client *c, struct wl_resource *r, int32_t w,
                          int32_t h, uint32_t format, uint32_t flags)
{
    (void)flags;
    struct dnc_params *p = wl_resource_get_user_data(r);
    struct dnc_dmabuf *buf = params_validate(r, p, w, h, format);
    if (!buf)
        return;
    struct wl_resource *res = buffer_create(c, buf, 0);
    if (res)
        zwp_linux_buffer_params_v1_send_created(r, res);
}

static void params_create_immed(struct wl_client *c, struct wl_resource *r,
                                uint32_t id, int32_t w, int32_t h,
                                uint32_t format, uint32_t flags)
{
    (void)flags;
    struct dnc_params *p = wl_resource_get_user_data(r);
    struct dnc_dmabuf *buf = params_validate(r, p, w, h, format);
    if (buf)
        buffer_create(c, buf, id);
}

static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = params_destroy_req,
    .add = params_add,
    .create = params_create,
    .create_immed = params_create_immed,
};

static void params_resource_destroy(struct wl_resource *r)
{
    struct dnc_params *p = wl_resource_get_user_data(r);
    if (p->buf)
        dmabuf_free(p->buf);
    free(p);
}

/* zwp_linux_dmabuf_v1 */

static void dmabuf_destroy_req(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void dmabuf_create_params(struct wl_client *c, struct wl_resource *r,
                                 uint32_t id)
{
    struct dnc_params *p = calloc(1, sizeof(*p));
    struct dnc_dmabuf *buf = calloc(1, sizeof(*buf));
    if (!p || !buf) {
        free(p);
        free(buf);
        wl_client_post_no_memory(c);
        return;
    }
    buf->fd = -1;
    p->buf = buf;
    p->resource = wl_resource_create(c, &zwp_linux_buffer_params_v1_interface,
                                     wl_resource_get_version(r), id);
    if (!p->resource) {
        free(p);
        free(buf);
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(p->resource, &params_impl, p,
                                   params_resource_destroy);
}

static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = dmabuf_destroy_req,
    .create_params = dmabuf_create_params,
};

static void bind_dmabuf(struct wl_client *c, void *data, uint32_t version,
                        uint32_t id)
{
    (void)data;
    struct wl_resource *r = wl_resource_create(c, &zwp_linux_dmabuf_v1_interface,
                                               (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &dmabuf_impl, NULL, NULL);
    for (size_t i = 0; i < DNC_ARRAY_LEN(supported_formats); i++) {
        zwp_linux_dmabuf_v1_send_format(r, supported_formats[i]);
        if (version >= ZWP_LINUX_DMABUF_V1_MODIFIER_SINCE_VERSION)
            zwp_linux_dmabuf_v1_send_modifier(r, supported_formats[i],
                                              (uint32_t)(DRM_FORMAT_MOD_LINEAR >> 32),
                                              (uint32_t)(DRM_FORMAT_MOD_LINEAR & 0xffffffff));
    }
}

bool dnc_dmabuf_init(struct dnc_server *srv)
{
    return wl_global_create(srv->display, &zwp_linux_dmabuf_v1_interface,
                            DMABUF_VERSION, srv, bind_dmabuf) != NULL;
}
