/*
 * EGL/GLES3 renderer on Android ANativeWindows.
 *
 * One EGL context is shared by all windows; a 1x1 pbuffer keeps it current
 * for uploads while no window is bound. Client buffers are copied with
 * glTexSubImage2D (GL_UNPACK_ROW_LENGTH needs GLES3). Byte order and alpha
 * of the DRM formats are fixed up in the fragment shader.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <android/native_window.h>
#include <stdlib.h>

#include "dnc_renderer.h"

#define TAG "dnc-gles"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define DRM_FORMAT_ARGB8888 0x34325241
#define DRM_FORMAT_XRGB8888 0x34325258
#define DRM_FORMAT_ABGR8888 0x34324241
#define DRM_FORMAT_XBGR8888 0x34324258

struct dnc_texture {
    GLuint id;
    int32_t width, height;
    bool swap_rb;       // memory order B,G,R,A (ARGB/XRGB8888 little endian)
    bool opaque;        // X formats: ignore alpha
};

struct dnc_rwindow {
    ANativeWindow *native;
    EGLSurface surface;
    int32_t width, height;
};

struct gles_state {
    EGLDisplay display;
    EGLConfig config;
    EGLContext context;
    EGLSurface pbuffer;
    GLuint program;
    GLint u_swap_rb, u_opaque, u_rect, u_viewport;
    GLuint vbo;
    struct dnc_rwindow *current;
    int32_t vp_w, vp_h;
};

/* Shaders */

static const char *vs_src =
    "#version 300 es\n"
    "layout(location = 0) in vec2 a_pos;\n"
    "uniform vec4 u_rect;\n"      // x, y, w, h in pixels
    "uniform vec2 u_viewport;\n"
    "out vec2 v_uv;\n"
    "void main() {\n"
    "    vec2 px = u_rect.xy + a_pos * u_rect.zw;\n"
    "    vec2 ndc = px / u_viewport * 2.0 - 1.0;\n"
    "    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);\n"
    "    v_uv = a_pos;\n"
    "}\n";

static const char *fs_src =
    "#version 300 es\n"
    "precision mediump float;\n"
    "uniform sampler2D u_tex;\n"
    "uniform bool u_swap_rb;\n"
    "uniform bool u_opaque;\n"
    "in vec2 v_uv;\n"
    "out vec4 frag;\n"
    "void main() {\n"
    "    vec4 c = texture(u_tex, v_uv);\n"
    "    if (u_swap_rb) c = c.bgra;\n"
    "    if (u_opaque) c.a = 1.0;\n"
    "    frag = c;\n"
    "}\n";

static GLuint compile(GLenum type, const char *src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        LOGE("shader: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static bool build_program(struct gles_state *st)
{
    GLuint vs = compile(GL_VERTEX_SHADER, vs_src);
    GLuint fs = compile(GL_FRAGMENT_SHADER, fs_src);
    if (!vs || !fs)
        return false;
    st->program = glCreateProgram();
    glAttachShader(st->program, vs);
    glAttachShader(st->program, fs);
    glLinkProgram(st->program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(st->program, GL_LINK_STATUS, &ok);
    if (!ok) {
        LOGE("program link failed");
        return false;
    }
    st->u_swap_rb = glGetUniformLocation(st->program, "u_swap_rb");
    st->u_opaque = glGetUniformLocation(st->program, "u_opaque");
    st->u_rect = glGetUniformLocation(st->program, "u_rect");
    st->u_viewport = glGetUniformLocation(st->program, "u_viewport");

    static const GLfloat quad[] = { 0, 0, 1, 0, 0, 1, 1, 1 };
    glGenBuffers(1, &st->vbo);
    glBindBuffer(GL_ARRAY_BUFFER, st->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    return true;
}

/* Context management */

static bool make_current(struct gles_state *st, struct dnc_rwindow *win)
{
    EGLSurface surf = win ? win->surface : st->pbuffer;
    if (!eglMakeCurrent(st->display, surf, surf, st->context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }
    st->current = win;
    return true;
}

static bool gles_init(void **state)
{
    struct gles_state *st = calloc(1, sizeof(*st));
    if (!st)
        return false;
    st->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (st->display == EGL_NO_DISPLAY || !eglInitialize(st->display, NULL, NULL))
        goto fail;

    static const EGLint cfg_attr[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint n = 0;
    if (!eglChooseConfig(st->display, cfg_attr, &st->config, 1, &n) || n < 1)
        goto fail;
    static const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    st->context = eglCreateContext(st->display, st->config, EGL_NO_CONTEXT, ctx_attr);
    if (st->context == EGL_NO_CONTEXT)
        goto fail;
    static const EGLint pb_attr[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    st->pbuffer = eglCreatePbufferSurface(st->display, st->config, pb_attr);
    if (st->pbuffer == EGL_NO_SURFACE || !make_current(st, NULL) || !build_program(st))
        goto fail;
    *state = st;
    return true;

fail:
    LOGE("EGL/GLES3 init failed: 0x%x", eglGetError());
    if (st->display != EGL_NO_DISPLAY)
        eglTerminate(st->display);
    free(st);
    return false;
}

static void gles_finish(void *state)
{
    struct gles_state *st = state;
    eglMakeCurrent(st->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(st->display, st->pbuffer);
    eglDestroyContext(st->display, st->context);
    eglTerminate(st->display);
    free(st);
}

/* Windows */

static struct dnc_rwindow *gles_window_create(void *state, void *native_window,
                                              int32_t w, int32_t h)
{
    struct gles_state *st = state;
    ANativeWindow *nw = native_window;
    if (!nw)
        return NULL;
    struct dnc_rwindow *win = calloc(1, sizeof(*win));
    if (!win) {
        ANativeWindow_release(nw);
        return NULL;
    }
    win->native = nw;
    win->width = w;
    win->height = h;
    ANativeWindow_setBuffersGeometry(nw, w, h, WINDOW_FORMAT_RGBA_8888);
    win->surface = eglCreateWindowSurface(st->display, st->config, nw, NULL);
    if (win->surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        ANativeWindow_release(nw);
        free(win);
        return NULL;
    }
    return win;
}

static void gles_window_resize(void *state, struct dnc_rwindow *win, int32_t w, int32_t h)
{
    (void)state;
    win->width = w;
    win->height = h;
    ANativeWindow_setBuffersGeometry(win->native, w, h,
                                     WINDOW_FORMAT_RGBA_8888);
}

static void gles_window_destroy(void *state, struct dnc_rwindow *win)
{
    struct gles_state *st = state;
    if (st->current == win)
        make_current(st, NULL);
    eglDestroySurface(st->display, win->surface);
    ANativeWindow_release(win->native);
    free(win);
}

/* Textures */

static bool gles_upload(void *state, struct dnc_texture **texp, const void *data,
                        int32_t w, int32_t h, int32_t stride, uint32_t format)
{
    struct gles_state *st = state;
    if (format != DRM_FORMAT_ARGB8888 && format != DRM_FORMAT_XRGB8888 &&
        format != DRM_FORMAT_ABGR8888 && format != DRM_FORMAT_XBGR8888)
        return false;
    if (!st->current && eglGetCurrentContext() != st->context)
        make_current(st, NULL);

    struct dnc_texture *tex = *texp;
    if (!tex) {
        tex = calloc(1, sizeof(*tex));
        if (!tex)
            return false;
        glGenTextures(1, &tex->id);
        *texp = tex;
    }
    glBindTexture(GL_TEXTURE_2D, tex->id);
    if (tex->width != w || tex->height != h) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        tex->width = w;
        tex->height = h;
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, stride / 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, data);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    tex->swap_rb = format == DRM_FORMAT_ARGB8888 || format == DRM_FORMAT_XRGB8888;
    tex->opaque = format == DRM_FORMAT_XRGB8888 || format == DRM_FORMAT_XBGR8888;
    return true;
}

static void gles_texture_destroy(void *state, struct dnc_texture *tex)
{
    (void)state;
    glDeleteTextures(1, &tex->id);
    free(tex);
}

/* Frames */

static bool gles_begin(void *state, struct dnc_rwindow *win)
{
    struct gles_state *st = state;
    if (!make_current(st, win))
        return false;
    st->vp_w = win->width;
    st->vp_h = win->height;
    glViewport(0, 0, win->width, win->height);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(st->program);
    glUniform2f(st->u_viewport, (GLfloat)win->width, (GLfloat)win->height);
    glBindBuffer(GL_ARRAY_BUFFER, st->vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);   // Wayland is premultiplied
    glActiveTexture(GL_TEXTURE0);
    return true;
}

static void gles_draw(void *state, struct dnc_texture *tex, int32_t x, int32_t y,
                      int32_t w, int32_t h)
{
    struct gles_state *st = state;
    glBindTexture(GL_TEXTURE_2D, tex->id);
    glUniform1i(st->u_swap_rb, tex->swap_rb);
    glUniform1i(st->u_opaque, tex->opaque);
    glUniform4f(st->u_rect, (GLfloat)x, (GLfloat)y, (GLfloat)w, (GLfloat)h);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static bool gles_end(void *state, struct dnc_rwindow *win)
{
    struct gles_state *st = state;
    if (!eglSwapBuffers(st->display, win->surface)) {
        LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

const struct dnc_renderer dnc_renderer_gles = {
    .init = gles_init,
    .finish = gles_finish,
    .window_create = gles_window_create,
    .window_resize = gles_window_resize,
    .window_destroy = gles_window_destroy,
    .upload = gles_upload,
    .texture_destroy = gles_texture_destroy,
    .begin = gles_begin,
    .draw = gles_draw,
    .end = gles_end,
};
