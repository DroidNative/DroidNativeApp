/*
 * Renderer interface of the compositor. Implementations:
 *   render_gles.c  EGL/GLES3 on Android ANativeWindows
 *   render_null.c  headless, for host tests
 * All calls happen on the compositor thread.
 */
#ifndef DNC_RENDERER_H
#define DNC_RENDERER_H

#include <stdbool.h>
#include <stdint.h>

struct dnc_texture;   // renderer-owned per-surface texture
struct dnc_rwindow;   // renderer-owned per-host-window state

struct dnc_renderer {
    bool (*init)(void **state);
    void (*finish)(void *state);

    struct dnc_rwindow *(*window_create)(void *state, void *native_window,
                                         int32_t width, int32_t height);
    void (*window_resize)(void *state, struct dnc_rwindow *win,
                          int32_t width, int32_t height);
    void (*window_destroy)(void *state, struct dnc_rwindow *win);

    // Copies a CPU-visible buffer into tex (created when *tex is NULL).
    bool (*upload)(void *state, struct dnc_texture **tex, const void *data,
                   int32_t width, int32_t height, int32_t stride,
                   uint32_t drm_format);
    void (*texture_destroy)(void *state, struct dnc_texture *tex);

    bool (*begin)(void *state, struct dnc_rwindow *win);
    // Destination rectangle in window pixels.
    void (*draw)(void *state, struct dnc_texture *tex, int32_t x, int32_t y,
                 int32_t width, int32_t height);
    bool (*end)(void *state, struct dnc_rwindow *win);
};

extern const struct dnc_renderer dnc_renderer_null;
#ifdef __ANDROID__
extern const struct dnc_renderer dnc_renderer_gles;
#endif

#endif
