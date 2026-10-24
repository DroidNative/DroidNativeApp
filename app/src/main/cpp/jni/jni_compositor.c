/* JNI glue for app.droidnative.display.Compositor */
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "dnc.h"
#include "dnc_renderer.h"

#define TAG "dnc"

struct jni_ctx {
    struct dnc_server *srv;
    jobject owner;              // global ref to the Kotlin Compositor
    JNIEnv *env;                // valid on the compositor thread during run
    jmethodID on_new, on_changed, on_closed, on_size, on_text_input, on_pointer_lock;
};

/* Callbacks (compositor thread) */

static void cb_toplevel(struct jni_ctx *ctx, jmethodID m, uint32_t id,
                        const char *app_id, const char *title, bool with_pid,
                        int32_t pid)
{
    JNIEnv *env = ctx->env;
    if (!env)
        return;
    jstring japp = (*env)->NewStringUTF(env, app_id);
    jstring jtitle = (*env)->NewStringUTF(env, title);
    if (with_pid)
        (*env)->CallVoidMethod(env, ctx->owner, m, (jint)id, japp, jtitle, (jint)pid);
    else
        (*env)->CallVoidMethod(env, ctx->owner, m, (jint)id, japp, jtitle);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, japp);
    (*env)->DeleteLocalRef(env, jtitle);
}

static void on_new(void *data, uint32_t id, const char *app_id, const char *title,
                   int32_t pid)
{
    struct jni_ctx *ctx = data;
    cb_toplevel(ctx, ctx->on_new, id, app_id, title, true, pid);
}

static void on_changed(void *data, uint32_t id, const char *app_id, const char *title)
{
    struct jni_ctx *ctx = data;
    cb_toplevel(ctx, ctx->on_changed, id, app_id, title, false, 0);
}

static void on_closed(void *data, uint32_t id)
{
    struct jni_ctx *ctx = data;
    JNIEnv *env = ctx->env;
    if (!env)
        return;
    (*env)->CallVoidMethod(env, ctx->owner, ctx->on_closed, (jint)id);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void on_size(void *data, uint32_t id, int32_t w, int32_t h)
{
    struct jni_ctx *ctx = data;
    JNIEnv *env = ctx->env;
    if (!env || !ctx->on_size)
        return;
    (*env)->CallVoidMethod(env, ctx->owner, ctx->on_size, (jint)id, (jint)w, (jint)h);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void on_text_input(void *data, uint32_t id, bool active)
{
    struct jni_ctx *ctx = data;
    JNIEnv *env = ctx->env;
    if (!env)
        return;
    (*env)->CallVoidMethod(env, ctx->owner, ctx->on_text_input, (jint)id,
                           (jboolean)active);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void on_pointer_lock(void *data, uint32_t id, bool locked)
{
    struct jni_ctx *ctx = data;
    JNIEnv *env = ctx->env;
    if (!env)
        return;
    (*env)->CallVoidMethod(env, ctx->owner, ctx->on_pointer_lock, (jint)id, (jboolean)locked);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void on_log(void *data, enum dnc_log_level level, const char *msg)
{
    (void)data;
    static const int prio[] = { ANDROID_LOG_DEBUG, ANDROID_LOG_INFO,
                                ANDROID_LOG_WARN, ANDROID_LOG_ERROR };
    __android_log_write(prio[level], TAG, msg);
}

/* Lifecycle */

#define JFN(name) Java_app_droidnative_display_Compositor_##name

JNIEXPORT jlong JNICALL JFN(nativeCreate)(JNIEnv *env, jobject self,
    jstring socket_path, jstring keymap_path, jstring tmp_dir, jint scale,
    jint disp_w, jint disp_h, jint phys_w, jint phys_h, jint refresh_mhz,
    jint touch_mode)
{
    struct jni_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx)
        return 0;
    jclass cls = (*env)->GetObjectClass(env, self);
    ctx->on_new = (*env)->GetMethodID(env, cls, "onToplevelNew", "(ILjava/lang/String;Ljava/lang/String;I)V");
    ctx->on_changed = (*env)->GetMethodID(env, cls, "onToplevelChanged", "(ILjava/lang/String;Ljava/lang/String;)V");
    ctx->on_closed = (*env)->GetMethodID(env, cls, "onToplevelClosed", "(I)V");
    ctx->on_size = (*env)->GetMethodID(env, cls, "onToplevelSize", "(III)V");
    ctx->on_text_input = (*env)->GetMethodID(env, cls, "onTextInput", "(IZ)V");
    ctx->on_pointer_lock = (*env)->GetMethodID(env, cls, "onPointerLock", "(IZ)V");
    if (!ctx->on_new || !ctx->on_changed || !ctx->on_closed) {
        free(ctx);
        return 0;
    }
    ctx->owner = (*env)->NewGlobalRef(env, self);

    const char *sock = (*env)->GetStringUTFChars(env, socket_path, NULL);
    const char *keymap = keymap_path ? (*env)->GetStringUTFChars(env, keymap_path, NULL) : NULL;
    const char *tmp = (*env)->GetStringUTFChars(env, tmp_dir, NULL);
    struct dnc_config cfg = {
        .socket_path = sock,
        .keymap_path = keymap,
        .tmp_dir = tmp,
        .scale = scale,
        .display_width = disp_w,
        .display_height = disp_h,
        .phys_width_mm = phys_w,
        .phys_height_mm = phys_h,
        .refresh_mhz = refresh_mhz,
        .touch_mode = touch_mode == 1 ? DNC_TOUCH_NATIVE : DNC_TOUCH_POINTER,
    };
    struct dnc_callbacks cb = {
        .toplevel_new = on_new,
        .toplevel_changed = on_changed,
        .toplevel_closed = on_closed,
        .toplevel_size = on_size,
        .text_input = on_text_input,
        .pointer_lock = on_pointer_lock,
        .log = on_log,
    };
    // The renderer needs EGL on the compositor thread; create() runs on the
    // same thread as run() (see Compositor.kt), so this is fine.
    ctx->srv = dnc_server_create(&cfg, &dnc_renderer_gles, &cb, ctx);
    (*env)->ReleaseStringUTFChars(env, socket_path, sock);
    if (keymap)
        (*env)->ReleaseStringUTFChars(env, keymap_path, keymap);
    (*env)->ReleaseStringUTFChars(env, tmp_dir, tmp);
    if (!ctx->srv) {
        (*env)->DeleteGlobalRef(env, ctx->owner);
        free(ctx);
        return 0;
    }
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL JFN(nativeRun)(JNIEnv *env, jobject self, jlong h)
{
    (void)self;
    struct jni_ctx *ctx = (struct jni_ctx *)(intptr_t)h;
    ctx->env = env;
    int r = dnc_server_run(ctx->srv);
    ctx->env = NULL;
    return r;
}

JNIEXPORT void JNICALL JFN(nativeStop)(JNIEnv *env, jobject self, jlong h)
{
    (void)env; (void)self;
    dnc_server_stop(((struct jni_ctx *)(intptr_t)h)->srv);
}

JNIEXPORT void JNICALL JFN(nativeDestroy)(JNIEnv *env, jobject self, jlong h)
{
    (void)self;
    struct jni_ctx *ctx = (struct jni_ctx *)(intptr_t)h;
    ctx->env = env;   // close callbacks fire while clients are torn down
    dnc_server_destroy(ctx->srv);
    (*env)->DeleteGlobalRef(env, ctx->owner);
    free(ctx);
}

/* Windows and input (any thread) */

#define SRV(h) (((struct jni_ctx *)(intptr_t)(h))->srv)

JNIEXPORT void JNICALL JFN(nativeAttach)(JNIEnv *env, jobject self, jlong h,
    jint id, jobject surface, jint w, jint hgt)
{
    (void)self;
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (win)
        dnc_window_attach(SRV(h), (uint32_t)id, win, w, hgt);
}

JNIEXPORT void JNICALL JFN(nativeDetach)(JNIEnv *env, jobject self, jlong h, jint id)
{
    (void)env; (void)self;
    dnc_window_detach(SRV(h), (uint32_t)id);
}

JNIEXPORT void JNICALL JFN(nativeResize)(JNIEnv *env, jobject self, jlong h,
    jint id, jint w, jint hgt)
{
    (void)env; (void)self;
    dnc_window_resize(SRV(h), (uint32_t)id, w, hgt);
}

JNIEXPORT void JNICALL JFN(nativeFocus)(JNIEnv *env, jobject self, jlong h,
    jint id, jboolean focused)
{
    (void)env; (void)self;
    dnc_window_focus(SRV(h), (uint32_t)id, focused);
}

JNIEXPORT void JNICALL JFN(nativeClose)(JNIEnv *env, jobject self, jlong h, jint id)
{
    (void)env; (void)self;
    dnc_toplevel_close(SRV(h), (uint32_t)id);
}

JNIEXPORT void JNICALL JFN(nativeSetKeymap)(JNIEnv *env, jobject self, jlong h, jstring path)
{
    (void)self;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p)
        return;
    dnc_set_keymap(SRV(h), p);
    (*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT void JNICALL JFN(nativeSetServerDecorations)(JNIEnv *env, jobject self,
    jlong h, jboolean server)
{
    (void)env; (void)self;
    dnc_set_server_decorations(SRV(h), server);
}

JNIEXPORT void JNICALL JFN(nativeKill)(JNIEnv *env, jobject self, jlong h, jint id)
{
    (void)env; (void)self;
    dnc_toplevel_kill(SRV(h), (uint32_t)id);
}

JNIEXPORT void JNICALL JFN(nativeTouch)(JNIEnv *env, jobject self, jlong h,
    jint id, jint action, jint slot, jfloat x, jfloat y, jint time)
{
    (void)env; (void)self;
    dnc_input_touch(SRV(h), (uint32_t)id, (enum dnc_touch_action)action, slot,
                    x, y, (uint32_t)time);
}

JNIEXPORT void JNICALL JFN(nativePointerMotion)(JNIEnv *env, jobject self,
    jlong h, jint id, jfloat x, jfloat y, jint time)
{
    (void)env; (void)self;
    dnc_input_pointer_motion(SRV(h), (uint32_t)id, x, y, (uint32_t)time);
}

JNIEXPORT void JNICALL JFN(nativePointerButton)(JNIEnv *env, jobject self,
    jlong h, jint id, jint button, jboolean pressed, jint time)
{
    (void)env; (void)self;
    dnc_input_pointer_button(SRV(h), (uint32_t)id, (uint32_t)button, pressed,
                             (uint32_t)time);
}

JNIEXPORT void JNICALL JFN(nativePointerAxis)(JNIEnv *env, jobject self,
    jlong h, jint id, jfloat dx, jfloat dy, jint time)
{
    (void)env; (void)self;
    dnc_input_pointer_axis(SRV(h), (uint32_t)id, dx, dy, (uint32_t)time);
}

JNIEXPORT void JNICALL JFN(nativePointerRelative)(JNIEnv *env, jobject self,
    jlong h, jint id, jfloat dx, jfloat dy, jint time)
{
    (void)env; (void)self;
    dnc_input_pointer_relative(SRV(h), (uint32_t)id, dx, dy, (uint32_t)time);
}

JNIEXPORT void JNICALL JFN(nativePointerWheel)(JNIEnv *env, jobject self,
    jlong h, jint id, jint steps_x, jint steps_y, jint time)
{
    (void)env; (void)self;
    dnc_input_pointer_wheel(SRV(h), (uint32_t)id, steps_x, steps_y, (uint32_t)time);
}

JNIEXPORT void JNICALL JFN(nativeKey)(JNIEnv *env, jobject self, jlong h,
    jint id, jint key, jboolean pressed, jint mods_depressed, jint mods_locked,
    jint time)
{
    (void)env; (void)self;
    dnc_input_key(SRV(h), (uint32_t)id, (uint32_t)key, pressed,
                  (uint32_t)mods_depressed, (uint32_t)mods_locked, (uint32_t)time);
}

// utf8: text encoded by the caller (JNI strings are modified UTF-8, which
// breaks emoji)
JNIEXPORT void JNICALL JFN(nativeText)(JNIEnv *env, jobject self, jlong h,
    jint id, jbyteArray utf8, jboolean preedit)
{
    (void)self;
    jsize len = (*env)->GetArrayLength(env, utf8);
    char *text = malloc((size_t)len + 1);
    if (!text)
        return;
    (*env)->GetByteArrayRegion(env, utf8, 0, len, (jbyte *)text);
    text[len] = '\0';
    if (preedit)
        dnc_input_text_preedit(SRV(h), (uint32_t)id, text);
    else
        dnc_input_text_commit(SRV(h), (uint32_t)id, text);
    free(text);
}

// out: commits, shm, dmabuf, mapFailures, presented, width, height, format,
//      kind, nonblack, hasWindow
JNIEXPORT jboolean JNICALL JFN(nativeStats)(JNIEnv *env, jobject self, jlong h,
    jint id, jlongArray out)
{
    (void)self;
    struct dnc_stats st;
    if (!dnc_get_stats(SRV(h), (uint32_t)id, &st))
        return JNI_FALSE;
    jlong v[11] = {
        (jlong)st.commits, (jlong)st.shm_commits, (jlong)st.dmabuf_commits,
        (jlong)st.dmabuf_map_failures, (jlong)st.frames_presented,
        st.last_width, st.last_height, (jlong)st.last_format,
        (jlong)st.last_kind, (jlong)st.last_nonblack, st.has_window ? 1 : 0,
    };
    (*env)->SetLongArrayRegion(env, out, 0, 11, v);
    return JNI_TRUE;
}
