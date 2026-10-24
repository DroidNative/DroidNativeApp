/*
 * Headless compositor for host tests: null renderer, every new toplevel gets
 * an 800x600 "window". Prints one JSON line per toplevel when it closes.
 * Usage: dnc-headless <socket-path> [keymap]   (stop it with SIGTERM)
 *
 * DNC_HEADLESS_DESKTOP=1 acts like the app in freeform windows: server
 * decorations on, and a toplevel gets its window only once its first frame
 * reported the size (printed as a "size" event; the window takes that size).
 */
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dnc.h"
#include "dnc_renderer.h"

static struct dnc_server *g_srv;
static bool g_desktop;

static void on_new(void *data, uint32_t id, const char *app_id, const char *title,
                   int32_t pid)
{
    (void)data; (void)pid;
    printf("{\"event\":\"new\",\"id\":%u,\"app_id\":\"%s\",\"title\":\"%s\"}\n",
           id, app_id, title);
    fflush(stdout);
    if (g_desktop)
        return;
    dnc_window_attach(g_srv, id, NULL, 800, 600);
    dnc_window_focus(g_srv, id, true);
}

static void on_size(void *data, uint32_t id, int32_t w, int32_t h)
{
    (void)data;
    printf("{\"event\":\"size\",\"id\":%u,\"width\":%d,\"height\":%d}\n", id, w, h);
    fflush(stdout);
    if (!g_desktop)
        return;
    dnc_window_attach(g_srv, id, NULL, w, h);
    dnc_window_focus(g_srv, id, true);
}

static void on_closed(void *data, uint32_t id)
{
    (void)data;
    struct dnc_stats st;
    if (dnc_get_stats(g_srv, id, &st)) {
        printf("{\"event\":\"closed\",\"id\":%u,\"commits\":%llu,\"shm\":%llu,"
               "\"dmabuf\":%llu,\"map_failures\":%llu,\"presented\":%llu,"
               "\"width\":%d,\"height\":%d,\"format\":%u,\"nonblack\":%u}\n",
               id, (unsigned long long)st.commits,
               (unsigned long long)st.shm_commits,
               (unsigned long long)st.dmabuf_commits,
               (unsigned long long)st.dmabuf_map_failures,
               (unsigned long long)st.frames_presented, st.last_width,
               st.last_height, st.last_format, st.last_nonblack);
        fflush(stdout);
    }
}

static void on_log(void *data, enum dnc_log_level level, const char *msg)
{
    (void)data;
    fprintf(stderr, "dnc[%d]: %s\n", level, msg);
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s <socket-path> [keymap]\n", argv[0]);
        return 2;
    }
    struct dnc_config cfg = {
        .socket_path = argv[1],
        .keymap_path = argc > 2 ? argv[2] : NULL,
        .tmp_dir = "/tmp",
        .scale = 1,
        .display_width = 800,
        .display_height = 600,
        .phys_width_mm = 70,
        .phys_height_mm = 140,
        .refresh_mhz = 60000,
    };
    struct dnc_callbacks cb = {
        .toplevel_new = on_new,
        .toplevel_closed = on_closed,
        .toplevel_size = on_size,
        .log = on_log,
    };
    g_srv = dnc_server_create(&cfg, &dnc_renderer_null, &cb, NULL);
    if (!g_srv)
        return 1;
    const char *desktop = getenv("DNC_HEADLESS_DESKTOP");
    g_desktop = desktop && strcmp(desktop, "1") == 0;
    if (g_desktop)
        dnc_set_server_decorations(g_srv, true);
    printf("{\"event\":\"ready\"}\n");
    fflush(stdout);
    int ret = dnc_server_run(g_srv);
    dnc_server_destroy(g_srv);
    return ret == 0 ? 0 : 1;
}
