/* Headless renderer for host tests: keeps sizes and a checksum, draws nothing */
#include <stdlib.h>

#include "dnc_renderer.h"

struct dnc_texture {
    int32_t width, height;
    uint32_t format;
    uint64_t checksum;
};

struct dnc_rwindow {
    int32_t width, height;
    uint64_t frames;
};

static bool null_init(void **state)
{
    *state = (void *)1;   // no state needed, must be non-NULL
    return true;
}

static void null_finish(void *state)
{
    (void)state;
}

static struct dnc_rwindow *null_window_create(void *state, void *native_window,
                                              int32_t w, int32_t h)
{
    (void)state; (void)native_window;
    struct dnc_rwindow *win = calloc(1, sizeof(*win));
    if (win) {
        win->width = w;
        win->height = h;
    }
    return win;
}

static void null_window_resize(void *state, struct dnc_rwindow *win, int32_t w, int32_t h)
{
    (void)state;
    win->width = w;
    win->height = h;
}

static void null_window_destroy(void *state, struct dnc_rwindow *win)
{
    (void)state;
    free(win);
}

static bool null_upload(void *state, struct dnc_texture **tex, const void *data,
                        int32_t w, int32_t h, int32_t stride, uint32_t format)
{
    (void)state;
    if (!*tex) {
        *tex = calloc(1, sizeof(**tex));
        if (!*tex)
            return false;
    }
    uint64_t sum = 1469598103934665603ull;   // FNV-1a over the pixel rows
    const unsigned char *row = data;
    for (int32_t y = 0; y < h; y++, row += stride) {
        for (int32_t i = 0; i < w * 4; i++) {
            sum ^= row[i];
            sum *= 1099511628211ull;
        }
    }
    (*tex)->width = w;
    (*tex)->height = h;
    (*tex)->format = format;
    (*tex)->checksum = sum;
    return true;
}

static void null_texture_destroy(void *state, struct dnc_texture *tex)
{
    (void)state;
    free(tex);
}

static bool null_begin(void *state, struct dnc_rwindow *win)
{
    (void)state; (void)win;
    return true;
}

static void null_draw(void *state, struct dnc_texture *tex, int32_t x, int32_t y,
                      int32_t w, int32_t h)
{
    (void)state; (void)tex; (void)x; (void)y; (void)w; (void)h;
}

static bool null_end(void *state, struct dnc_rwindow *win)
{
    (void)state;
    win->frames++;
    return true;
}

const struct dnc_renderer dnc_renderer_null = {
    .init = null_init,
    .finish = null_finish,
    .window_create = null_window_create,
    .window_resize = null_window_resize,
    .window_destroy = null_window_destroy,
    .upload = null_upload,
    .texture_destroy = null_texture_destroy,
    .begin = null_begin,
    .draw = null_draw,
    .end = null_end,
};
