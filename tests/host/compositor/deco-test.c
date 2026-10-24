/*
 * Client for run.sh: checks the KDE server decoration protocol (the one GTK 3
 * uses) and the first-frame size report. Binds the manager, prints its
 * default mode, asks for server decorations on a toplevel and draws one
 * 300x200 frame after the initial configure, like a program that picks its
 * own size. Then waits for the configure that sizes the window.
 * Output: "default_mode N mode M configured WxH"
 * Usage: dnc-decotest   (WAYLAND_DISPLAY and XDG_RUNTIME_DIR set)
 */
#define _GNU_SOURCE
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <wayland-client.h>

#include "server-decoration-client-protocol.h"
#include "xdg-shell-client-protocol.h"

#define W 300
#define H 200

static struct wl_compositor *compositor;
static struct wl_shm *shm;
static struct xdg_wm_base *wm_base;
static struct org_kde_kwin_server_decoration_manager *deco_manager;
static int default_mode = -1, deco_mode = -1;
static int configures;
static int32_t cfg_w, cfg_h;

/* Listeners */

static void on_default_mode(void *data, struct org_kde_kwin_server_decoration_manager *m,
                            uint32_t mode)
{
    (void)data; (void)m;
    default_mode = (int)mode;
}

static const struct org_kde_kwin_server_decoration_manager_listener manager_listener = {
    .default_mode = on_default_mode,
};

static void on_mode(void *data, struct org_kde_kwin_server_decoration *d, uint32_t mode)
{
    (void)data; (void)d;
    deco_mode = (int)mode;
}

static const struct org_kde_kwin_server_decoration_listener deco_listener = {
    .mode = on_mode,
};

static void on_ping(void *data, struct xdg_wm_base *b, uint32_t serial)
{
    (void)data;
    xdg_wm_base_pong(b, serial);
}

static const struct xdg_wm_base_listener wm_base_listener = { .ping = on_ping };

static void on_surface_configure(void *data, struct xdg_surface *s, uint32_t serial)
{
    (void)data;
    xdg_surface_ack_configure(s, serial);
    configures++;
}

static const struct xdg_surface_listener surface_listener = {
    .configure = on_surface_configure,
};

static void on_toplevel_configure(void *data, struct xdg_toplevel *t, int32_t w, int32_t h,
                                  struct wl_array *states)
{
    (void)data; (void)t; (void)states;
    cfg_w = w;
    cfg_h = h;
}

static void on_toplevel_close(void *data, struct xdg_toplevel *t)
{
    (void)data; (void)t;
}

static const struct xdg_toplevel_listener toplevel_listener = {
    .configure = on_toplevel_configure,
    .close = on_toplevel_close,
};

static void on_global(void *data, struct wl_registry *r, uint32_t name,
                      const char *iface, uint32_t version)
{
    (void)data; (void)version;
    if (strcmp(iface, wl_compositor_interface.name) == 0)
        compositor = wl_registry_bind(r, name, &wl_compositor_interface, 4);
    else if (strcmp(iface, wl_shm_interface.name) == 0)
        shm = wl_registry_bind(r, name, &wl_shm_interface, 1);
    else if (strcmp(iface, xdg_wm_base_interface.name) == 0)
        wm_base = wl_registry_bind(r, name, &xdg_wm_base_interface, 1);
    else if (strcmp(iface, org_kde_kwin_server_decoration_manager_interface.name) == 0)
        deco_manager = wl_registry_bind(r, name,
                                        &org_kde_kwin_server_decoration_manager_interface, 1);
}

static void on_global_remove(void *data, struct wl_registry *r, uint32_t name)
{
    (void)data; (void)r; (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global = on_global,
    .global_remove = on_global_remove,
};

/* Helpers */

static struct wl_buffer *make_buffer(void)
{
    int fd = memfd_create("dnc-decotest", MFD_CLOEXEC);
    if (fd < 0 || ftruncate(fd, W * H * 4) < 0)
        return NULL;
    uint32_t *px = mmap(NULL, W * H * 4, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (px == MAP_FAILED)
        return NULL;
    for (int i = 0; i < W * H; i++)
        px[i] = 0xff3070c0;
    munmap(px, W * H * 4);
    struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, W * H * 4);
    struct wl_buffer *b = wl_shm_pool_create_buffer(pool, 0, W, H, W * 4, WL_SHM_FORMAT_XRGB8888);
    wl_shm_pool_destroy(pool);
    close(fd);
    return b;
}

// Dispatches until cond is true or about timeout_ms passed
static int dispatch_until(struct wl_display *d, int *counter, int target, int timeout_ms)
{
    for (int waited = 0; *counter < target && waited < timeout_ms; waited += 50) {
        wl_display_flush(d);
        struct pollfd p = { .fd = wl_display_get_fd(d), .events = POLLIN };
        if (poll(&p, 1, 50) > 0 && wl_display_dispatch(d) < 0)
            return -1;
        wl_display_dispatch_pending(d);
    }
    return *counter >= target ? 0 : -1;
}

int main(void)
{
    struct wl_display *d = wl_display_connect(NULL);
    if (!d) {
        fprintf(stderr, "no display\n");
        return 1;
    }
    struct wl_registry *reg = wl_display_get_registry(d);
    wl_registry_add_listener(reg, &registry_listener, NULL);
    wl_display_roundtrip(d);
    if (!compositor || !shm || !wm_base || !deco_manager) {
        fprintf(stderr, "missing globals (decoration manager %s)\n", deco_manager ? "ok" : "missing");
        return 1;
    }
    org_kde_kwin_server_decoration_manager_add_listener(deco_manager, &manager_listener, NULL);
    xdg_wm_base_add_listener(wm_base, &wm_base_listener, NULL);
    wl_display_roundtrip(d);

    struct wl_surface *surf = wl_compositor_create_surface(compositor);
    struct xdg_surface *xs = xdg_wm_base_get_xdg_surface(wm_base, surf);
    xdg_surface_add_listener(xs, &surface_listener, NULL);
    struct xdg_toplevel *tl = xdg_surface_get_toplevel(xs);
    xdg_toplevel_add_listener(tl, &toplevel_listener, NULL);
    xdg_toplevel_set_app_id(tl, "dnc-decotest");
    struct org_kde_kwin_server_decoration *deco =
        org_kde_kwin_server_decoration_manager_create(deco_manager, surf);
    org_kde_kwin_server_decoration_add_listener(deco, &deco_listener, NULL);
    org_kde_kwin_server_decoration_request_mode(deco, ORG_KDE_KWIN_SERVER_DECORATION_MODE_SERVER);
    wl_surface_commit(surf);
    if (dispatch_until(d, &configures, 1, 2000) < 0) {
        fprintf(stderr, "no initial configure\n");
        return 1;
    }

    // Own size after the initial (0x0) configure
    struct wl_buffer *buf = make_buffer();
    if (!buf)
        return 1;
    wl_surface_attach(surf, buf, 0, 0);
    wl_surface_damage(surf, 0, 0, W, H);
    wl_surface_commit(surf);
    // The window the host opens for that size configures the toplevel again
    dispatch_until(d, &configures, 2, 2000);
    printf("default_mode %d mode %d configured %dx%d\n", default_mode, deco_mode, cfg_w, cfg_h);

    org_kde_kwin_server_decoration_release(deco);
    xdg_toplevel_destroy(tl);
    xdg_surface_destroy(xs);
    wl_surface_destroy(surf);
    wl_buffer_destroy(buf);
    wl_display_roundtrip(d);
    wl_display_disconnect(d);
    return 0;
}
