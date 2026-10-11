/*
 * dn-wltest: minimal Wayland client used by the DroidNative test suites.
 *
 * Opens an xdg_toplevel, renders an animated gradient into wl_shm (or a
 * "dmabuf" made from a memfd, to exercise the linux-dmabuf protocol path)
 * and waits for a frame callback after every commit.
 *
 * Usage: dn-wltest [--mode shm|dmabuf] [--frames N] [--app-id ID]
 *                  [--timeout-ms MS]
 * Exit:  0 all frames were acknowledged, 1 protocol/setup error, 2 timeout.
 * Prints one JSON line with the result.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <wayland-client.h>

#include "xdg-shell-client-protocol.h"
#include "linux-dmabuf-unstable-v1-client-protocol.h"

#define DRM_FORMAT_XRGB8888 0x34325258

/* State */

struct buffer {
    struct wl_buffer *buffer;
    void *data;
    bool busy;
};

struct app {
    struct wl_display *display;
    struct wl_registry *registry;
    struct wl_compositor *compositor;
    struct wl_shm *shm;
    struct xdg_wm_base *wm_base;
    struct zwp_linux_dmabuf_v1 *dmabuf;
    struct wl_surface *surface;
    struct xdg_surface *xdg_surface;
    struct xdg_toplevel *toplevel;

    bool use_dmabuf;
    bool dmabuf_linear_xrgb;
    int width, height;
    int pending_w, pending_h;
    bool configured;
    bool closed;
    bool frame_done;
    struct buffer buffers[2];
    int frames_done;
};

static uint64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

static int create_anon(size_t size)
{
    int fd = (int)syscall(SYS_memfd_create, "dn-wltest", 1u);
    if (fd < 0) {
        char path[] = "/tmp/dn-wltest-XXXXXX";
        fd = mkostemp(path, O_CLOEXEC);
        if (fd < 0)
            return -1;
        unlink(path);
    }
    if (ftruncate(fd, (off_t)size) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Buffers */

static void buffer_release(void *data, struct wl_buffer *b)
{
    (void)b;
    ((struct buffer *)data)->busy = false;
}

static const struct wl_buffer_listener buffer_listener = { buffer_release };

static bool buffer_init(struct app *app, struct buffer *buf)
{
    int stride = app->width * 4;
    size_t size = (size_t)stride * (size_t)app->height;
    int fd = create_anon(size);
    if (fd < 0)
        return false;
    buf->data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (buf->data == MAP_FAILED) {
        close(fd);
        return false;
    }
    if (app->use_dmabuf) {
        struct zwp_linux_buffer_params_v1 *params =
            zwp_linux_dmabuf_v1_create_params(app->dmabuf);
        zwp_linux_buffer_params_v1_add(params, fd, 0, 0, (uint32_t)stride, 0, 0);
        buf->buffer = zwp_linux_buffer_params_v1_create_immed(
            params, app->width, app->height, DRM_FORMAT_XRGB8888, 0);
        zwp_linux_buffer_params_v1_destroy(params);
    } else {
        struct wl_shm_pool *pool = wl_shm_create_pool(app->shm, fd, (int32_t)size);
        buf->buffer = wl_shm_pool_create_buffer(pool, 0, app->width, app->height,
                                                stride, WL_SHM_FORMAT_XRGB8888);
        wl_shm_pool_destroy(pool);
    }
    close(fd);
    wl_buffer_add_listener(buf->buffer, &buffer_listener, buf);
    return true;
}

static void buffer_fini(struct app *app, struct buffer *buf)
{
    if (buf->buffer) {
        wl_buffer_destroy(buf->buffer);
        munmap(buf->data, (size_t)app->width * 4 * (size_t)app->height);
    }
    memset(buf, 0, sizeof(*buf));
}

static void paint(struct app *app, struct buffer *buf, int frame)
{
    uint32_t *px = buf->data;
    for (int y = 0; y < app->height; y++) {
        for (int x = 0; x < app->width; x++) {
            uint32_t r = (uint32_t)(x * 255 / app->width);
            uint32_t g = (uint32_t)(y * 255 / app->height);
            uint32_t b = (uint32_t)((frame * 4) & 0xff);
            px[y * app->width + x] = 0xff000000u | r << 16 | g << 8 | b;
        }
    }
}

/* Protocol listeners */

static void frame_done(void *data, struct wl_callback *cb, uint32_t t)
{
    (void)t;
    struct app *app = data;
    wl_callback_destroy(cb);
    app->frame_done = true;
    app->frames_done++;
}

static const struct wl_callback_listener frame_listener = { frame_done };

static void wm_ping(void *data, struct xdg_wm_base *wm, uint32_t serial)
{
    (void)data;
    xdg_wm_base_pong(wm, serial);
}

static const struct xdg_wm_base_listener wm_listener = { wm_ping };

static void xdg_surface_configure(void *data, struct xdg_surface *s, uint32_t serial)
{
    struct app *app = data;
    xdg_surface_ack_configure(s, serial);
    if (app->pending_w > 0 && app->pending_h > 0 &&
        (app->pending_w != app->width || app->pending_h != app->height)) {
        for (int i = 0; i < 2; i++)
            buffer_fini(app, &app->buffers[i]);
        app->width = app->pending_w;
        app->height = app->pending_h;
    }
    app->configured = true;
}

static const struct xdg_surface_listener xdg_surface_listener = { xdg_surface_configure };

static void toplevel_configure(void *data, struct xdg_toplevel *t, int32_t w,
                               int32_t h, struct wl_array *states)
{
    (void)t; (void)states;
    struct app *app = data;
    app->pending_w = w;
    app->pending_h = h;
}

static void toplevel_close(void *data, struct xdg_toplevel *t)
{
    (void)t;
    ((struct app *)data)->closed = true;
}

static void toplevel_bounds(void *data, struct xdg_toplevel *t, int32_t w, int32_t h)
{
    (void)data; (void)t; (void)w; (void)h;
}

static void toplevel_caps(void *data, struct xdg_toplevel *t, struct wl_array *caps)
{
    (void)data; (void)t; (void)caps;
}

static const struct xdg_toplevel_listener toplevel_listener = {
    .configure = toplevel_configure,
    .close = toplevel_close,
    .configure_bounds = toplevel_bounds,
    .wm_capabilities = toplevel_caps,
};

static void dmabuf_format(void *data, struct zwp_linux_dmabuf_v1 *d, uint32_t f)
{
    (void)data; (void)d; (void)f;
}

static void dmabuf_modifier(void *data, struct zwp_linux_dmabuf_v1 *d, uint32_t f,
                            uint32_t hi, uint32_t lo)
{
    (void)d;
    struct app *app = data;
    if (f == DRM_FORMAT_XRGB8888 && hi == 0 && lo == 0)
        app->dmabuf_linear_xrgb = true;
}

static const struct zwp_linux_dmabuf_v1_listener dmabuf_listener = {
    .format = dmabuf_format,
    .modifier = dmabuf_modifier,
};

static void registry_global(void *data, struct wl_registry *reg, uint32_t name,
                            const char *iface, uint32_t version)
{
    struct app *app = data;
    if (strcmp(iface, wl_compositor_interface.name) == 0) {
        app->compositor = wl_registry_bind(reg, name, &wl_compositor_interface, 4);
    } else if (strcmp(iface, wl_shm_interface.name) == 0) {
        app->shm = wl_registry_bind(reg, name, &wl_shm_interface, 1);
    } else if (strcmp(iface, xdg_wm_base_interface.name) == 0) {
        app->wm_base = wl_registry_bind(reg, name, &xdg_wm_base_interface, 1);
        xdg_wm_base_add_listener(app->wm_base, &wm_listener, app);
    } else if (strcmp(iface, zwp_linux_dmabuf_v1_interface.name) == 0 && version >= 3) {
        app->dmabuf = wl_registry_bind(reg, name, &zwp_linux_dmabuf_v1_interface, 3);
        zwp_linux_dmabuf_v1_add_listener(app->dmabuf, &dmabuf_listener, app);
    }
}

static void registry_remove(void *data, struct wl_registry *reg, uint32_t name)
{
    (void)data; (void)reg; (void)name;
}

static const struct wl_registry_listener registry_listener = {
    registry_global, registry_remove,
};

/* Main loop */

// Dispatches until cond is true or the deadline passes. 0 ok, 1 error, 2 timeout
static int wait_for(struct app *app, bool *cond, uint64_t deadline)
{
    while (!*cond) {
        if (wl_display_dispatch_pending(app->display) < 0)
            return 1;
        if (*cond)
            break;
        if (wl_display_flush(app->display) < 0 && errno != EAGAIN)
            return 1;
        uint64_t now = now_ms();
        if (now >= deadline)
            return 2;
        struct pollfd pfd = { wl_display_get_fd(app->display), POLLIN, 0 };
        if (wl_display_prepare_read(app->display) != 0)
            continue;
        int r = poll(&pfd, 1, (int)(deadline - now));
        if (r > 0) {
            if (wl_display_read_events(app->display) < 0)
                return 1;
        } else {
            wl_display_cancel_read(app->display);
            if (r < 0 && errno != EINTR)
                return 1;
        }
    }
    return 0;
}

int main(int argc, char **argv)
{
    struct app app = { .width = 320, .height = 240 };
    int frames = 120;
    int timeout_ms = 20000;
    const char *app_id = "org.droidnative.wltest";

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--mode") && i + 1 < argc)
            app.use_dmabuf = !strcmp(argv[++i], "dmabuf");
        else if (!strcmp(argv[i], "--frames") && i + 1 < argc)
            frames = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--app-id") && i + 1 < argc)
            app_id = argv[++i];
        else if (!strcmp(argv[i], "--timeout-ms") && i + 1 < argc)
            timeout_ms = atoi(argv[++i]);
        else {
            fprintf(stderr, "usage: %s [--mode shm|dmabuf] [--frames N] "
                            "[--app-id ID] [--timeout-ms MS]\n", argv[0]);
            return 1;
        }
    }

    const char *mode = app.use_dmabuf ? "dmabuf" : "shm";
    app.display = wl_display_connect(NULL);
    if (!app.display) {
        printf("{\"result\":\"error\",\"reason\":\"connect\",\"mode\":\"%s\"}\n", mode);
        return 1;
    }
    app.registry = wl_display_get_registry(app.display);
    wl_registry_add_listener(app.registry, &registry_listener, &app);
    wl_display_roundtrip(app.display);
    wl_display_roundtrip(app.display);

    if (!app.compositor || !app.wm_base || !app.shm ||
        (app.use_dmabuf && (!app.dmabuf || !app.dmabuf_linear_xrgb))) {
        printf("{\"result\":\"error\",\"reason\":\"globals\",\"mode\":\"%s\"}\n", mode);
        return 1;
    }

    app.surface = wl_compositor_create_surface(app.compositor);
    app.xdg_surface = xdg_wm_base_get_xdg_surface(app.wm_base, app.surface);
    xdg_surface_add_listener(app.xdg_surface, &xdg_surface_listener, &app);
    app.toplevel = xdg_surface_get_toplevel(app.xdg_surface);
    xdg_toplevel_add_listener(app.toplevel, &toplevel_listener, &app);
    xdg_toplevel_set_app_id(app.toplevel, app_id);
    xdg_toplevel_set_title(app.toplevel, "DroidNative Wayland test");
    wl_surface_commit(app.surface);

    uint64_t start = now_ms();
    uint64_t deadline = start + (uint64_t)timeout_ms;
    int rc = wait_for(&app, &app.configured, deadline);

    for (int f = 0; rc == 0 && f < frames && !app.closed; f++) {
        struct buffer *buf = NULL;
        for (int i = 0; i < 2; i++) {
            if (!app.buffers[i].busy) {
                buf = &app.buffers[i];
                break;
            }
        }
        if (!buf) {
            // Both buffers held by the compositor: wait for a release
            wl_display_roundtrip(app.display);
            f--;
            if (now_ms() >= deadline)
                rc = 2;
            continue;
        }
        if (!buf->buffer && !buffer_init(&app, buf)) {
            rc = 1;
            break;
        }
        paint(&app, buf, f);
        wl_surface_attach(app.surface, buf->buffer, 0, 0);
        wl_surface_damage(app.surface, 0, 0, app.width, app.height);
        struct wl_callback *cb = wl_surface_frame(app.surface);
        wl_callback_add_listener(cb, &frame_listener, &app);
        app.frame_done = false;
        buf->busy = true;
        wl_surface_commit(app.surface);
        rc = wait_for(&app, &app.frame_done, deadline);
    }

    uint64_t elapsed = now_ms() - start;
    double fps = elapsed > 0 ? app.frames_done * 1000.0 / (double)elapsed : 0;
    printf("{\"result\":\"%s\",\"mode\":\"%s\",\"frames\":%d,\"size\":\"%dx%d\","
           "\"elapsed_ms\":%llu,\"fps\":%.1f}\n",
           rc == 0 ? "ok" : rc == 2 ? "timeout" : "error", mode, app.frames_done,
           app.width, app.height, (unsigned long long)elapsed, fps);
    fflush(stdout);

    for (int i = 0; i < 2; i++)
        buffer_fini(&app, &app.buffers[i]);
    xdg_toplevel_destroy(app.toplevel);
    xdg_surface_destroy(app.xdg_surface);
    wl_surface_destroy(app.surface);
    if (app.dmabuf)
        zwp_linux_dmabuf_v1_destroy(app.dmabuf);
    xdg_wm_base_destroy(app.wm_base);
    wl_shm_destroy(app.shm);
    wl_compositor_destroy(app.compositor);
    wl_registry_destroy(app.registry);
    wl_display_roundtrip(app.display);
    wl_display_disconnect(app.display);
    return rc;
}
