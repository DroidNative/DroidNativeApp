/* Internal state of the compositor, shared by the protocol modules */
#ifndef DNC_PRIVATE_H
#define DNC_PRIVATE_H

#include <pthread.h>
#include <stdarg.h>
#include <wayland-server-core.h>

#include "dnc.h"
#include "dnc_renderer.h"

#define DNC_ARRAY_LEN(a) (sizeof(a) / sizeof((a)[0]))

struct dnc_server;
struct dnc_surface;
struct dnc_toplevel;

/* Buffers */

struct dnc_dmabuf {
    struct wl_resource *resource;
    int fd;
    uint32_t offset;
    uint32_t stride;
    int32_t width;
    int32_t height;
    uint32_t format;
    void *map;          // lazily mapped, MAP_FAILED once mapping failed
    size_t map_size;
};

// Returns NULL if resource is not one of our dmabuf wl_buffers.
struct dnc_dmabuf *dnc_dmabuf_from_resource(struct wl_resource *resource);

/* Surfaces */

enum dnc_role {
    DNC_ROLE_NONE,
    DNC_ROLE_XDG_TOPLEVEL,
    DNC_ROLE_XDG_POPUP,
    DNC_ROLE_SUBSURFACE,
    DNC_ROLE_CURSOR,
};

struct dnc_subsurface {
    struct wl_resource *resource;
    struct dnc_surface *surface;
    struct dnc_surface *parent;
    struct wl_list link;            // dnc_surface.subsurfaces of parent
    int32_t x, y;
    int32_t pending_x, pending_y;
};

struct dnc_surface {
    struct dnc_server *server;
    struct wl_resource *resource;
    struct wl_list link;            // dnc_server.surfaces

    struct {
        struct wl_resource *buffer;
        struct wl_listener buffer_destroy;
        bool attached;              // attach was called (buffer may be NULL)
        int32_t dx, dy;
        int32_t scale;
        struct wl_list frame_callbacks;
    } pending;

    struct wl_list frame_callbacks; // waiting for the next present
    struct dnc_texture *texture;
    bool mapped;                    // has content
    int32_t buffer_width, buffer_height;
    int32_t scale;

    enum dnc_role role;
    void *role_data;                // dnc_xdg_surface or dnc_subsurface
    struct wl_list subsurfaces;     // dnc_subsurface.link
};

struct dnc_surface *dnc_surface_from_resource(struct wl_resource *resource);
// Logical size (buffer size / scale)
int32_t dnc_surface_width(const struct dnc_surface *s);
int32_t dnc_surface_height(const struct dnc_surface *s);
// The toplevel that displays this surface (via xdg/subsurface parents)
struct dnc_toplevel *dnc_surface_get_toplevel(struct dnc_surface *s);
void dnc_surface_send_frame_done(struct dnc_surface *s, uint32_t time_ms);

/* xdg-shell */

struct dnc_popup;

struct dnc_xdg_surface {
    struct dnc_server *server;
    struct wl_resource *resource;
    struct dnc_surface *surface;
    struct wl_resource *role_resource;   // xdg_toplevel or xdg_popup
    struct dnc_toplevel *toplevel;
    struct dnc_popup *popup;
    bool configured;                     // client acked a configure
    uint32_t last_serial;
    bool has_geometry;
    int32_t geo_x, geo_y, geo_w, geo_h;
    int32_t pending_geo_x, pending_geo_y, pending_geo_w, pending_geo_h;
    bool pending_has_geometry;
    struct dnc_xwl_window *x11;          // X window (no xdg resources), else NULL
};

struct dnc_popup {
    struct dnc_xdg_surface *xdg;
    struct dnc_xdg_surface *parent;
    struct wl_list link;                 // dnc_toplevel.popups
    int32_t x, y;                        // relative to parent surface origin
    int32_t width, height;
};

struct dnc_window {
    struct dnc_rwindow *rwin;
    int32_t width, height;               // pixels
    bool dirty;
};

struct dnc_toplevel {
    struct dnc_server *server;
    uint32_t id;
    struct wl_list link;                 // dnc_server.toplevels
    struct dnc_xdg_surface *xdg;
    char *title;
    char *app_id;
    bool announced;                      // toplevel_new callback sent
    struct dnc_window *window;           // NULL when no host window
    struct wl_list popups;               // dnc_popup.link
    int32_t configured_w, configured_h;  // logical
    bool activated;
    bool fullscreen;                     // client asked for fullscreen
    int32_t min_w, min_h, max_w, max_h;  // logical, 0 unset
    struct dnc_stats stats;              // guarded by server->stats_lock
};

struct dnc_toplevel *dnc_toplevel_by_id(struct dnc_server *srv, uint32_t id);
// For roles without an xdg_toplevel resource (Xwayland windows)
struct dnc_toplevel *dnc_toplevel_create(struct dnc_xdg_surface *xdg);
void dnc_toplevel_destroy(struct dnc_toplevel *tl);
void dnc_toplevel_configure(struct dnc_toplevel *tl);
void dnc_xdg_surface_commit(struct dnc_xdg_surface *xdg);
void dnc_toplevel_schedule_repaint(struct dnc_toplevel *tl);
void dnc_toplevel_dismiss_popups(struct dnc_toplevel *tl);

/* Window pixel = offset + logical * zoom. Content that does not match its
 * window (fixed size, fullscreen at another size) is zoomed to fit and
 * centred instead of being drawn 1:1 from the corner. */
struct dnc_view {
    double zoom, x, y;
};
struct dnc_view dnc_toplevel_view(struct dnc_toplevel *tl);

/* Server */

struct dnc_cmd;

struct dnc_seat_state {
    struct wl_list seat_resources;
    struct wl_list pointers;
    struct wl_list keyboards;
    struct wl_list touches;
    struct dnc_surface *pointer_focus;
    struct dnc_surface *keyboard_focus;
    struct wl_listener pointer_focus_destroy;
    struct wl_listener keyboard_focus_destroy;
    double pointer_sx, pointer_sy;       // surface-local, logical
    uint32_t buttons_down;
    struct dnc_surface *touch_focus[16];
    uint32_t mods_depressed, mods_locked;
    int keymap_fd;
    uint32_t keymap_size;
};

struct dnc_server {
    struct dnc_config config;
    char *socket_path;
    const struct dnc_renderer *renderer;
    void *renderer_state;
    struct dnc_callbacks callbacks;
    void *callback_data;

    struct wl_display *display;
    struct wl_event_loop *loop;
    int listen_fd;

    struct wl_list surfaces;
    struct wl_list toplevels;
    uint32_t next_toplevel_id;

    struct dnc_seat_state seat;

    struct wl_list text_inputs;          // text_input.link
    struct wl_list constraints;          // pointer constraints (constraints.c)
    struct wl_list relative_pointers;    // zwp_relative_pointer_v1 resources
    struct constraint *active_constraint;
    struct wl_list outputs;              // wl_output resources
    struct wl_list xwl_surfaces;         // xwayland_surface_v1 resources
    struct wl_list xwl_windows;          // dnc_xwl_window.link
    struct wl_resource *xwm;             // dn_xwm_v1 of dn-xwm, NULL if none
    struct dnc_xwl_window *xwl_active;   // X toplevel that popups belong to
    bool text_input_active;              // last state told to the host
    uint32_t text_input_toplevel;

    struct wl_event_source *repaint_idle;
    struct wl_event_source *hidden_frame_timer;
    bool hidden_timer_armed;

    pthread_mutex_t cmd_lock;
    struct dnc_cmd *cmd_head, *cmd_tail;
    int cmd_fd;
    struct wl_event_source *cmd_source;

    pthread_mutex_t stats_lock;
    bool running;
};

uint32_t dnc_now_ms(void);
void dnc_log(struct dnc_server *srv, enum dnc_log_level level,
             const char *fmt, ...) __attribute__((format(printf, 3, 4)));
void dnc_schedule_repaint(struct dnc_server *srv);
void dnc_arm_hidden_frame_timer(struct dnc_server *srv);
// Creates a sealed read-only-shareable file containing data, -1 on error.
int dnc_create_shared_file(struct dnc_server *srv, const void *data, size_t size);

/* Module init (globals) */

bool dnc_compositor_init(struct dnc_server *srv);
bool dnc_xdg_shell_init(struct dnc_server *srv);
bool dnc_dmabuf_init(struct dnc_server *srv);
bool dnc_seat_init(struct dnc_server *srv);
void dnc_seat_finish(struct dnc_server *srv);
bool dnc_output_init(struct dnc_server *srv);
bool dnc_data_device_init(struct dnc_server *srv);
bool dnc_decoration_init(struct dnc_server *srv);
bool dnc_text_input_init(struct dnc_server *srv);
bool dnc_xwayland_init(struct dnc_server *srv);
bool dnc_constraints_init(struct dnc_server *srv);
// Focus changed: (de)activate pointer constraints
void dnc_constraints_update(struct dnc_server *srv);
bool dnc_pointer_locked(struct dnc_server *srv);
// Movement for zwp_relative_pointer_v1 clients of the pointer focus
void dnc_relative_motion(struct dnc_server *srv, double dx, double dy, uint32_t time_ms);
void dnc_xwayland_finish(struct dnc_server *srv);
void dnc_output_client_size(struct dnc_server *srv, struct wl_client *client,
                            int32_t width, int32_t height);

/* Xwayland (xwayland.c) */

struct dnc_xwl_window;
// The host window wants a size / activation: tell the X window manager
void dnc_xwayland_configure(struct dnc_toplevel *tl);
void dnc_xwayland_close(struct dnc_toplevel *tl);
void dnc_xwayland_kill(struct dnc_toplevel *tl);
// Host pid of the X client owning the toplevel (_NET_WM_PID), 0 if unknown
int32_t dnc_xwayland_pid(const struct dnc_xwl_window *win);

/* Text input (zwp_text_input_v3) */

// Keyboard focus moved from old to new (either may be NULL).
void dnc_text_input_focus(struct dnc_server *srv, struct dnc_surface *old,
                          struct dnc_surface *new);
// The focused surface was destroyed: no leave can be sent for it.
void dnc_text_input_focus_lost(struct dnc_server *srv);
// Both return false when no text field is active in the focused client.
bool dnc_text_input_commit(struct dnc_server *srv, const char *text);
bool dnc_text_input_preedit(struct dnc_server *srv, const char *text);

/* Seat helpers used by the server command handlers */

void dnc_seat_surface_destroyed(struct dnc_server *srv, struct dnc_surface *s);
void dnc_seat_touch(struct dnc_server *srv, struct dnc_toplevel *tl,
                    enum dnc_touch_action action, int32_t slot,
                    double x, double y, uint32_t time);
void dnc_seat_pointer_motion(struct dnc_server *srv, struct dnc_toplevel *tl,
                             double x, double y, uint32_t time);
void dnc_seat_pointer_button(struct dnc_server *srv, struct dnc_toplevel *tl,
                             uint32_t button, bool pressed, uint32_t time);
void dnc_seat_pointer_axis(struct dnc_server *srv, struct dnc_toplevel *tl,
                           double dx, double dy, uint32_t time);
void dnc_seat_pointer_wheel(struct dnc_server *srv, struct dnc_toplevel *tl,
                            int32_t steps_x, int32_t steps_y, uint32_t time);
void dnc_seat_key(struct dnc_server *srv, struct dnc_toplevel *tl,
                  uint32_t key, bool pressed, uint32_t mods_depressed,
                  uint32_t mods_locked, uint32_t time);
void dnc_seat_keyboard_focus(struct dnc_server *srv, struct dnc_toplevel *tl);
// After seat.keymap_fd changed: send the keymap to every wl_keyboard
void dnc_seat_keymap_changed(struct dnc_server *srv);
// Gives the pointer to s at its current position (pointer locks)
void dnc_seat_pointer_focus_surface(struct dnc_server *srv, struct dnc_surface *s);
// Relative movement while the pointer is locked
void dnc_seat_pointer_relative(struct dnc_server *srv, struct dnc_toplevel *tl,
                               double dx, double dy, uint32_t time);

#endif
