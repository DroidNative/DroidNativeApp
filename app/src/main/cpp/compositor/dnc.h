/*
 * dnc: the DroidNative Wayland compositor.
 *
 * Runs one wl_display on its own thread. Each xdg_toplevel is shown in its
 * own host window (an Android Surface, or nothing in headless tests).
 * Every function below except dnc_server_create/run/destroy is thread-safe:
 * calls are queued and executed on the compositor thread.
 */
#ifndef DNC_H
#define DNC_H

#include <stdbool.h>
#include <stdint.h>

/* Configuration */

enum dnc_touch_mode {
    DNC_TOUCH_POINTER = 0,  // touch drives wl_pointer (desktop apps)
    DNC_TOUCH_NATIVE = 1,   // touch is sent as wl_touch
};

struct dnc_config {
    const char *socket_path;   // absolute path of the Wayland socket
    const char *keymap_path;   // xkb keymap text file, NULL: no keyboard
    const char *tmp_dir;       // fallback for anonymous files (no memfd)
    int32_t scale;             // integer wl_output scale, >= 1
    int32_t display_width;     // physical display, pixels
    int32_t display_height;
    int32_t phys_width_mm;
    int32_t phys_height_mm;
    int32_t refresh_mhz;
    enum dnc_touch_mode touch_mode;
};

/* Callbacks, invoked on the compositor thread */

enum dnc_log_level { DNC_LOG_DEBUG, DNC_LOG_INFO, DNC_LOG_WARN, DNC_LOG_ERROR };

struct dnc_callbacks {
    // pid: the client process (host pid; PRoot does not virtualise pids)
    void (*toplevel_new)(void *data, uint32_t id, const char *app_id,
                         const char *title, int32_t pid);
    void (*toplevel_changed)(void *data, uint32_t id, const char *app_id,
                             const char *title);
    void (*toplevel_closed)(void *data, uint32_t id);
    // First frame of a toplevel drawn before it had a host window: the size
    // the program chose for itself, in window pixels (logical x scale). Lets
    // the host open a freeform window of that size.
    void (*toplevel_size)(void *data, uint32_t id, int32_t width, int32_t height);
    // A text field of the toplevel became active or inactive: show or hide
    // the on-screen keyboard. Repeated "active" means "show it again".
    void (*text_input)(void *data, uint32_t id, bool active);
    // A program locked (captured) or released the pointer in this toplevel:
    // the host should send relative motion (dnc_input_pointer_relative)
    void (*pointer_lock)(void *data, uint32_t id, bool locked);
    void (*log)(void *data, enum dnc_log_level level, const char *msg);
};

/* Statistics, used by the diagnostics and device tests */

enum dnc_buffer_kind { DNC_BUF_NONE = 0, DNC_BUF_SHM = 1, DNC_BUF_DMABUF = 2 };

struct dnc_stats {
    uint64_t commits;              // buffer commits on the toplevel tree
    uint64_t shm_commits;
    uint64_t dmabuf_commits;
    uint64_t dmabuf_map_failures;
    uint64_t frames_presented;     // host window presents
    int32_t last_width;            // last root surface buffer, pixels
    int32_t last_height;
    uint32_t last_format;          // DRM fourcc
    enum dnc_buffer_kind last_kind;
    uint32_t last_nonblack;        // of 64 sampled pixels in the last buffer
    bool has_window;
};

struct dnc_renderer;
struct dnc_server;

/* Lifecycle (call from the owning thread) */

struct dnc_server *dnc_server_create(const struct dnc_config *config,
                                     const struct dnc_renderer *renderer,
                                     const struct dnc_callbacks *callbacks,
                                     void *callback_data);
// Blocks running the event loop until dnc_server_stop() is called.
int dnc_server_run(struct dnc_server *srv);
void dnc_server_destroy(struct dnc_server *srv);

/* Thread-safe */

void dnc_server_stop(struct dnc_server *srv);

// native_window is handed to the renderer, which owns it from then on.
void dnc_window_attach(struct dnc_server *srv, uint32_t id, void *native_window,
                       int32_t width, int32_t height);
void dnc_window_detach(struct dnc_server *srv, uint32_t id);
void dnc_window_resize(struct dnc_server *srv, uint32_t id, int32_t width,
                       int32_t height);
void dnc_window_focus(struct dnc_server *srv, uint32_t id, bool focused);
void dnc_toplevel_close(struct dnc_server *srv, uint32_t id);
// Ends the client of the toplevel: disconnects a Wayland client, has the X
// window manager kill an X client. For clients that ignored a close.
void dnc_toplevel_kill(struct dnc_server *srv, uint32_t id);

enum dnc_touch_action {
    DNC_TOUCH_DOWN, DNC_TOUCH_UP, DNC_TOUCH_MOTION, DNC_TOUCH_CANCEL,
};

// Coordinates are window pixels.
void dnc_input_touch(struct dnc_server *srv, uint32_t id,
                     enum dnc_touch_action action, int32_t slot,
                     float x, float y, uint32_t time_ms);
void dnc_input_pointer_motion(struct dnc_server *srv, uint32_t id,
                              float x, float y, uint32_t time_ms);
// button is a Linux evdev code (BTN_LEFT, ...)
void dnc_input_pointer_button(struct dnc_server *srv, uint32_t id,
                              uint32_t button, bool pressed, uint32_t time_ms);
void dnc_input_pointer_axis(struct dnc_server *srv, uint32_t id,
                            float dx, float dy, uint32_t time_ms);
// Pointer movement while a program has locked the pointer; window pixels
void dnc_input_pointer_relative(struct dnc_server *srv, uint32_t id,
                                float dx, float dy, uint32_t time_ms);
// Wheel notches, positive = down/right (like a mouse wheel)
void dnc_input_pointer_wheel(struct dnc_server *srv, uint32_t id,
                             int32_t steps_x, int32_t steps_y, uint32_t time_ms);
// key is a Linux evdev code; mods are xkb modifier masks of the us keymap
void dnc_input_key(struct dnc_server *srv, uint32_t id, uint32_t key,
                   bool pressed, uint32_t mods_depressed,
                   uint32_t mods_locked, uint32_t time_ms);

// Text from the host input method (UTF-8) for the active text field of the
// focused client. preedit is the text still being composed ("" clears it).
// Ignored when the client has no active text field.
void dnc_input_text_commit(struct dnc_server *srv, uint32_t id, const char *text);
void dnc_input_text_preedit(struct dnc_server *srv, uint32_t id, const char *text);

// Replaces the xkb keymap (text file at path) for all clients.
void dnc_set_keymap(struct dnc_server *srv, const char *path);

// GTK 3 windows created from now on leave the title bar to the host (true:
// freeform and desktop windows have their own) or draw their own (false).
void dnc_set_server_decorations(struct dnc_server *srv, bool server);

bool dnc_get_stats(struct dnc_server *srv, uint32_t id, struct dnc_stats *out);

#endif
