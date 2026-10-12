/* Display setup, event loop, cross-thread command queue, repaint scheduling */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "dnc_private.h"
#include "xdg-shell-server-protocol.h"

/* Logging and helpers */

uint32_t dnc_now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

void dnc_log(struct dnc_server *srv, enum dnc_log_level level,
             const char *fmt, ...)
{
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (srv && srv->callbacks.log)
        srv->callbacks.log(srv->callback_data, level, buf);
    else
        fprintf(stderr, "dnc: %s\n", buf);
}

int dnc_create_shared_file(struct dnc_server *srv, const void *data, size_t size)
{
    int fd = -1;
#ifdef SYS_memfd_create
    // bionic only has the wrapper from API 30, the syscall is older
    fd = (int)syscall(SYS_memfd_create, "dnc-shared", 1u /* MFD_CLOEXEC */);
#endif
    if (fd < 0) {
        char path[512];
        snprintf(path, sizeof(path), "%s/dnc-shared-XXXXXX",
                 srv->config.tmp_dir ? srv->config.tmp_dir : "/tmp");
        fd = mkostemp(path, O_CLOEXEC);
        if (fd < 0)
            return -1;
        unlink(path);
    }
    const char *p = data;
    size_t left = size;
    while (left > 0) {
        ssize_t n = write(fd, p, left);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            close(fd);
            return -1;
        }
        p += n;
        left -= (size_t)n;
    }
    return fd;
}

/* Command queue */

enum dnc_cmd_type {
    CMD_STOP,
    CMD_WINDOW_ATTACH,
    CMD_WINDOW_DETACH,
    CMD_WINDOW_RESIZE,
    CMD_WINDOW_FOCUS,
    CMD_TOPLEVEL_CLOSE,
    CMD_TOPLEVEL_KILL,
    CMD_TOUCH,
    CMD_POINTER_MOTION,
    CMD_POINTER_BUTTON,
    CMD_POINTER_AXIS,
    CMD_POINTER_WHEEL,
    CMD_POINTER_RELATIVE,
    CMD_KEY,
    CMD_SET_KEYMAP,
    CMD_TEXT_COMMIT,
    CMD_TEXT_PREEDIT,
};

struct dnc_cmd {
    enum dnc_cmd_type type;
    uint32_t id;
    void *native_window;
    int32_t width, height;
    int32_t action, slot;
    float x, y;
    uint32_t a, b, c;
    bool flag;
    uint32_t time;
    char *text;                 // owned by the command
    struct dnc_cmd *next;
};

static void cmd_push(struct dnc_server *srv, const struct dnc_cmd *tmpl)
{
    struct dnc_cmd *cmd = malloc(sizeof(*cmd));
    if (!cmd)
        return;
    *cmd = *tmpl;
    cmd->next = NULL;
    pthread_mutex_lock(&srv->cmd_lock);
    if (srv->cmd_tail)
        srv->cmd_tail->next = cmd;
    else
        srv->cmd_head = cmd;
    srv->cmd_tail = cmd;
    pthread_mutex_unlock(&srv->cmd_lock);
    uint64_t one = 1;
    ssize_t r = write(srv->cmd_fd, &one, sizeof(one));
    (void)r;
}

static void window_destroy(struct dnc_server *srv, struct dnc_toplevel *tl)
{
    if (!tl->window)
        return;
    srv->renderer->window_destroy(srv->renderer_state, tl->window->rwin);
    free(tl->window);
    tl->window = NULL;
    pthread_mutex_lock(&srv->stats_lock);
    tl->stats.has_window = false;
    pthread_mutex_unlock(&srv->stats_lock);
}

// Window size in pixels -> configured toplevel size in logical units
static void window_apply_size(struct dnc_toplevel *tl, int32_t w, int32_t h)
{
    int32_t scale = tl->server->config.scale;
    tl->configured_w = w / scale;
    tl->configured_h = h / scale;
    // Before the configure: SDL sizes fullscreen windows by the output
    if (tl->xdg && !tl->xdg->x11 && tl->xdg->surface)
        dnc_output_client_size(tl->server, wl_resource_get_client(tl->xdg->surface->resource), w, h);
    dnc_toplevel_configure(tl);
}

static void replace_keymap(struct dnc_server *srv, const char *path);

static void cmd_execute(struct dnc_server *srv, struct dnc_cmd *cmd)
{
    struct dnc_toplevel *tl = NULL;
    if (cmd->type != CMD_STOP && cmd->type != CMD_SET_KEYMAP) {
        tl = dnc_toplevel_by_id(srv, cmd->id);
        if (!tl) {
            // The toplevel went away before the host saw the close callback
            if (cmd->type == CMD_WINDOW_ATTACH && cmd->native_window) {
                struct dnc_rwindow *rw = srv->renderer->window_create(
                    srv->renderer_state, cmd->native_window, 1, 1);
                if (rw)
                    srv->renderer->window_destroy(srv->renderer_state, rw);
            }
            return;
        }
    }

    switch (cmd->type) {
    case CMD_STOP:
        srv->running = false;
        break;
    case CMD_WINDOW_ATTACH: {
        window_destroy(srv, tl);
        struct dnc_window *win = calloc(1, sizeof(*win));
        if (!win)
            break;
        win->rwin = srv->renderer->window_create(srv->renderer_state,
                                                 cmd->native_window,
                                                 cmd->width, cmd->height);
        if (!win->rwin) {
            dnc_log(srv, DNC_LOG_ERROR, "window_create failed for toplevel %u", tl->id);
            free(win);
            break;
        }
        win->width = cmd->width;
        win->height = cmd->height;
        win->dirty = true;
        tl->window = win;
        pthread_mutex_lock(&srv->stats_lock);
        tl->stats.has_window = true;
        pthread_mutex_unlock(&srv->stats_lock);
        window_apply_size(tl, cmd->width, cmd->height);
        dnc_toplevel_schedule_repaint(tl);
        break;
    }
    case CMD_WINDOW_DETACH:
        window_destroy(srv, tl);
        dnc_arm_hidden_frame_timer(srv);
        break;
    case CMD_WINDOW_RESIZE:
        if (tl->window) {
            tl->window->width = cmd->width;
            tl->window->height = cmd->height;
            srv->renderer->window_resize(srv->renderer_state, tl->window->rwin,
                                         cmd->width, cmd->height);
            dnc_toplevel_schedule_repaint(tl);
        }
        window_apply_size(tl, cmd->width, cmd->height);
        break;
    case CMD_WINDOW_FOCUS:
        tl->activated = cmd->flag;
        dnc_toplevel_configure(tl);
        if (cmd->flag)
            dnc_seat_keyboard_focus(srv, tl);
        break;
    case CMD_TOPLEVEL_CLOSE:
        if (tl->xdg && tl->xdg->x11)
            dnc_xwayland_close(tl);
        else if (tl->xdg && tl->xdg->role_resource)
            xdg_toplevel_send_close(tl->xdg->role_resource);
        break;
    case CMD_TOPLEVEL_KILL:
        if (tl->xdg && tl->xdg->x11) {
            dnc_xwayland_kill(tl);
        } else if (tl->xdg && tl->xdg->resource) {
            dnc_log(srv, DNC_LOG_INFO, "toplevel %u did not close, disconnecting its client", tl->id);
            wl_client_destroy(wl_resource_get_client(tl->xdg->resource));
        }
        break;
    case CMD_TOUCH:
        dnc_seat_touch(srv, tl, (enum dnc_touch_action)cmd->action, cmd->slot,
                       cmd->x, cmd->y, cmd->time);
        break;
    case CMD_POINTER_MOTION:
        dnc_seat_pointer_motion(srv, tl, cmd->x, cmd->y, cmd->time);
        break;
    case CMD_POINTER_BUTTON:
        dnc_seat_pointer_button(srv, tl, cmd->a, cmd->flag, cmd->time);
        break;
    case CMD_POINTER_AXIS:
        dnc_seat_pointer_axis(srv, tl, cmd->x, cmd->y, cmd->time);
        break;
    case CMD_POINTER_RELATIVE:
        dnc_seat_pointer_relative(srv, tl, cmd->x, cmd->y, cmd->time);
        break;
    case CMD_POINTER_WHEEL:
        dnc_seat_pointer_wheel(srv, tl, (int32_t)cmd->a, (int32_t)cmd->b, cmd->time);
        break;
    case CMD_KEY:
        dnc_seat_key(srv, tl, cmd->a, cmd->flag, cmd->b, cmd->c, cmd->time);
        break;
    case CMD_SET_KEYMAP:
        if (cmd->text)
            replace_keymap(srv, cmd->text);
        break;
    case CMD_TEXT_COMMIT:
        if (cmd->text)
            dnc_text_input_commit(srv, cmd->text);
        break;
    case CMD_TEXT_PREEDIT:
        if (cmd->text)
            dnc_text_input_preedit(srv, cmd->text);
        break;
    }
}

static int handle_cmd_fd(int fd, uint32_t mask, void *data)
{
    struct dnc_server *srv = data;
    (void)mask;
    uint64_t count;
    ssize_t r = read(fd, &count, sizeof(count));
    (void)r;

    pthread_mutex_lock(&srv->cmd_lock);
    struct dnc_cmd *cmd = srv->cmd_head;
    srv->cmd_head = srv->cmd_tail = NULL;
    pthread_mutex_unlock(&srv->cmd_lock);

    while (cmd) {
        struct dnc_cmd *next = cmd->next;
        cmd_execute(srv, cmd);
        free(cmd->text);
        free(cmd);
        cmd = next;
    }
    return 0;
}

/* Public thread-safe API */

void dnc_server_stop(struct dnc_server *srv)
{
    struct dnc_cmd c = { .type = CMD_STOP };
    cmd_push(srv, &c);
}

void dnc_window_attach(struct dnc_server *srv, uint32_t id, void *native_window,
                       int32_t width, int32_t height)
{
    struct dnc_cmd c = { .type = CMD_WINDOW_ATTACH, .id = id,
                         .native_window = native_window,
                         .width = width, .height = height };
    cmd_push(srv, &c);
}

void dnc_window_detach(struct dnc_server *srv, uint32_t id)
{
    struct dnc_cmd c = { .type = CMD_WINDOW_DETACH, .id = id };
    cmd_push(srv, &c);
}

void dnc_window_resize(struct dnc_server *srv, uint32_t id, int32_t width,
                       int32_t height)
{
    struct dnc_cmd c = { .type = CMD_WINDOW_RESIZE, .id = id,
                         .width = width, .height = height };
    cmd_push(srv, &c);
}

void dnc_window_focus(struct dnc_server *srv, uint32_t id, bool focused)
{
    struct dnc_cmd c = { .type = CMD_WINDOW_FOCUS, .id = id, .flag = focused };
    cmd_push(srv, &c);
}

void dnc_toplevel_close(struct dnc_server *srv, uint32_t id)
{
    struct dnc_cmd c = { .type = CMD_TOPLEVEL_CLOSE, .id = id };
    cmd_push(srv, &c);
}

void dnc_toplevel_kill(struct dnc_server *srv, uint32_t id)
{
    struct dnc_cmd c = { .type = CMD_TOPLEVEL_KILL, .id = id };
    cmd_push(srv, &c);
}

void dnc_input_touch(struct dnc_server *srv, uint32_t id,
                     enum dnc_touch_action action, int32_t slot,
                     float x, float y, uint32_t time_ms)
{
    struct dnc_cmd c = { .type = CMD_TOUCH, .id = id, .action = action,
                         .slot = slot, .x = x, .y = y, .time = time_ms };
    cmd_push(srv, &c);
}

void dnc_input_pointer_motion(struct dnc_server *srv, uint32_t id,
                              float x, float y, uint32_t time_ms)
{
    struct dnc_cmd c = { .type = CMD_POINTER_MOTION, .id = id,
                         .x = x, .y = y, .time = time_ms };
    cmd_push(srv, &c);
}

void dnc_input_pointer_button(struct dnc_server *srv, uint32_t id,
                              uint32_t button, bool pressed, uint32_t time_ms)
{
    struct dnc_cmd c = { .type = CMD_POINTER_BUTTON, .id = id, .a = button,
                         .flag = pressed, .time = time_ms };
    cmd_push(srv, &c);
}

void dnc_input_pointer_axis(struct dnc_server *srv, uint32_t id,
                            float dx, float dy, uint32_t time_ms)
{
    struct dnc_cmd c = { .type = CMD_POINTER_AXIS, .id = id,
                         .x = dx, .y = dy, .time = time_ms };
    cmd_push(srv, &c);
}

void dnc_input_pointer_relative(struct dnc_server *srv, uint32_t id,
                                float dx, float dy, uint32_t time_ms)
{
    struct dnc_cmd c = { .type = CMD_POINTER_RELATIVE, .id = id, .x = dx, .y = dy, .time = time_ms };
    cmd_push(srv, &c);
}

void dnc_input_pointer_wheel(struct dnc_server *srv, uint32_t id,
                             int32_t steps_x, int32_t steps_y, uint32_t time_ms)
{
    struct dnc_cmd c = { .type = CMD_POINTER_WHEEL, .id = id, .a = (uint32_t)steps_x,
                         .b = (uint32_t)steps_y, .time = time_ms };
    cmd_push(srv, &c);
}

void dnc_input_key(struct dnc_server *srv, uint32_t id, uint32_t key,
                   bool pressed, uint32_t mods_depressed,
                   uint32_t mods_locked, uint32_t time_ms)
{
    struct dnc_cmd c = { .type = CMD_KEY, .id = id, .a = key, .flag = pressed,
                         .b = mods_depressed, .c = mods_locked,
                         .time = time_ms };
    cmd_push(srv, &c);
}

void dnc_set_keymap(struct dnc_server *srv, const char *path)
{
    struct dnc_cmd c = { .type = CMD_SET_KEYMAP, .text = strdup(path) };
    cmd_push(srv, &c);
}

void dnc_input_text_commit(struct dnc_server *srv, uint32_t id, const char *text)
{
    struct dnc_cmd c = { .type = CMD_TEXT_COMMIT, .id = id, .text = strdup(text) };
    cmd_push(srv, &c);
}

void dnc_input_text_preedit(struct dnc_server *srv, uint32_t id, const char *text)
{
    struct dnc_cmd c = { .type = CMD_TEXT_PREEDIT, .id = id, .text = strdup(text) };
    cmd_push(srv, &c);
}

bool dnc_get_stats(struct dnc_server *srv, uint32_t id, struct dnc_stats *out)
{
    // Toplevel list is only mutated on the compositor thread while holding
    // stats_lock, so walking it here under the same lock is safe.
    bool found = false;
    pthread_mutex_lock(&srv->stats_lock);
    struct dnc_toplevel *tl;
    wl_list_for_each(tl, &srv->toplevels, link) {
        if (tl->id == id) {
            *out = tl->stats;
            found = true;
            break;
        }
    }
    pthread_mutex_unlock(&srv->stats_lock);
    return found;
}

/* Repaint */

struct dnc_view dnc_toplevel_view(struct dnc_toplevel *tl)
{
    double scale = tl->server->config.scale;
    struct dnc_view v = { scale, 0, 0 };
    struct dnc_window *win = tl->window;
    if (!win || !tl->xdg || !tl->xdg->surface)
        return v;
    struct dnc_xdg_surface *xdg = tl->xdg;
    int32_t cw = xdg->has_geometry ? xdg->geo_w : dnc_surface_width(xdg->surface);
    int32_t ch = xdg->has_geometry ? xdg->geo_h : dnc_surface_height(xdg->surface);
    if (cw <= 0 || ch <= 0)
        return v;
    // Matching content: the configured size is the window divided by the
    // scale, rounded down
    double dw = win->width - cw * scale, dh = win->height - ch * scale;
    if (dw >= 0 && dw < scale && dh >= 0 && dh < scale)
        return v;
    v.zoom = fmin((double)win->width / cw, (double)win->height / ch);
    v.x = (win->width - cw * v.zoom) / 2;
    v.y = (win->height - ch * v.zoom) / 2;
    return v;
}

// x, y: logical position in the toplevel's coordinates
static void draw_surface_tree(struct dnc_server *srv, const struct dnc_view *v,
                              struct dnc_surface *s, int32_t x, int32_t y)
{
    if (s->mapped && s->texture) {
        // Edges rounded on their own, so neighbouring subsurfaces meet
        long x0 = lround(v->x + x * v->zoom), y0 = lround(v->y + y * v->zoom);
        long x1 = lround(v->x + (x + dnc_surface_width(s)) * v->zoom);
        long y1 = lround(v->y + (y + dnc_surface_height(s)) * v->zoom);
        srv->renderer->draw(srv->renderer_state, s->texture, (int32_t)x0, (int32_t)y0,
                            (int32_t)(x1 - x0), (int32_t)(y1 - y0));
    }
    struct dnc_subsurface *sub;
    wl_list_for_each_reverse(sub, &s->subsurfaces, link)
        draw_surface_tree(srv, v, sub->surface, x + sub->x, y + sub->y);
}

static void send_frames_tree(struct dnc_surface *s, uint32_t time)
{
    dnc_surface_send_frame_done(s, time);
    struct dnc_subsurface *sub;
    wl_list_for_each(sub, &s->subsurfaces, link)
        send_frames_tree(sub->surface, time);
}

// Popup position relative to the toplevel's root surface origin
static void popup_origin(struct dnc_popup *popup, int32_t *x, int32_t *y)
{
    int32_t px = popup->x, py = popup->y;
    struct dnc_xdg_surface *parent = popup->parent;
    while (parent && parent->popup) {
        px += parent->popup->x;
        py += parent->popup->y;
        parent = parent->popup->parent;
    }
    *x = px;
    *y = py;
}

static void repaint_toplevel(struct dnc_server *srv, struct dnc_toplevel *tl)
{
    struct dnc_window *win = tl->window;
    struct dnc_surface *root = tl->xdg->surface;
    if (!srv->renderer->begin(srv->renderer_state, win->rwin))
        return;

    // The window shows the toplevel's window geometry (no CSD shadows)
    int32_t ox = tl->xdg->has_geometry ? -tl->xdg->geo_x : 0;
    int32_t oy = tl->xdg->has_geometry ? -tl->xdg->geo_y : 0;
    struct dnc_view view = dnc_toplevel_view(tl);
    if (view.zoom != tl->logged_zoom) {
        // Content that does not fill its window: worth a line when it changes
        tl->logged_zoom = view.zoom;
        dnc_log(srv, DNC_LOG_DEBUG, "toplevel %u: window %dx%d, content %dx%d%s, zoom %.3f",
                tl->id, win->width, win->height,
                tl->xdg->has_geometry ? tl->xdg->geo_w : dnc_surface_width(root),
                tl->xdg->has_geometry ? tl->xdg->geo_h : dnc_surface_height(root),
                tl->xdg->has_geometry ? " (geometry)" : "", view.zoom);
    }
    draw_surface_tree(srv, &view, root, ox, oy);

    struct dnc_popup *popup;
    wl_list_for_each(popup, &tl->popups, link) {
        if (!popup->xdg || !popup->xdg->surface)
            continue;
        int32_t px, py;
        popup_origin(popup, &px, &py);
        int32_t gx = popup->xdg->has_geometry ? popup->xdg->geo_x : 0;
        int32_t gy = popup->xdg->has_geometry ? popup->xdg->geo_y : 0;
        draw_surface_tree(srv, &view, popup->xdg->surface, ox + px - gx, oy + py - gy);
    }

    bool ok = srv->renderer->end(srv->renderer_state, win->rwin);
    win->dirty = false;

    uint32_t now = dnc_now_ms();
    send_frames_tree(root, now);
    wl_list_for_each(popup, &tl->popups, link) {
        if (popup->xdg && popup->xdg->surface)
            send_frames_tree(popup->xdg->surface, now);
    }

    if (ok) {
        pthread_mutex_lock(&srv->stats_lock);
        tl->stats.frames_presented++;
        pthread_mutex_unlock(&srv->stats_lock);
    }
}

static void handle_repaint(void *data)
{
    struct dnc_server *srv = data;
    srv->repaint_idle = NULL;
    struct dnc_toplevel *tl;
    wl_list_for_each(tl, &srv->toplevels, link) {
        if (tl->window && tl->window->dirty && tl->xdg && tl->xdg->surface &&
            tl->xdg->surface->mapped)
            repaint_toplevel(srv, tl);
    }
    dnc_arm_hidden_frame_timer(srv);
}

void dnc_schedule_repaint(struct dnc_server *srv)
{
    if (!srv->repaint_idle)
        srv->repaint_idle = wl_event_loop_add_idle(srv->loop, handle_repaint, srv);
}

void dnc_toplevel_schedule_repaint(struct dnc_toplevel *tl)
{
    if (tl->window) {
        tl->window->dirty = true;
        dnc_schedule_repaint(tl->server);
    }
}

// Surfaces that are not visible in any host window still get frame
// callbacks (throttled), otherwise FIFO clients block forever.
#define HIDDEN_FRAME_INTERVAL_MS 100

static bool surface_is_visible(struct dnc_surface *s)
{
    struct dnc_toplevel *tl = dnc_surface_get_toplevel(s);
    return tl && tl->window && tl->xdg && tl->xdg->surface &&
           tl->xdg->surface->mapped;
}

static int handle_hidden_frames(void *data)
{
    struct dnc_server *srv = data;
    srv->hidden_timer_armed = false;
    uint32_t now = dnc_now_ms();
    bool pending = false;
    struct dnc_surface *s;
    wl_list_for_each(s, &srv->surfaces, link) {
        if (wl_list_empty(&s->frame_callbacks))
            continue;
        if (!surface_is_visible(s))
            dnc_surface_send_frame_done(s, now);
        else
            pending = true;
    }
    (void)pending;
    return 0;
}

void dnc_arm_hidden_frame_timer(struct dnc_server *srv)
{
    if (srv->hidden_timer_armed)
        return;
    struct dnc_surface *s;
    wl_list_for_each(s, &srv->surfaces, link) {
        if (!wl_list_empty(&s->frame_callbacks) && !surface_is_visible(s)) {
            wl_event_source_timer_update(srv->hidden_frame_timer,
                                         HIDDEN_FRAME_INTERVAL_MS);
            srv->hidden_timer_armed = true;
            return;
        }
    }
}

/* Lifecycle */

static int bind_socket(const char *path)
{
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    if (strlen(path) >= sizeof(addr.sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    strcpy(addr.sun_path, path);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0)
        return -1;
    unlink(path);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0 ||
        listen(fd, 128) < 0) {
        int e = errno;
        close(fd);
        errno = e;
        return -1;
    }
    return fd;
}

static bool load_keymap_file(struct dnc_server *srv, const char *path)
{
    srv->seat.keymap_fd = -1;
    if (!path)
        return true;
    FILE *f = fopen(path, "rb");
    if (!f) {
        dnc_log(srv, DNC_LOG_WARN, "no keymap at %s, keyboard disabled", path);
        return true;
    }
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = len > 0 ? malloc((size_t)len + 1) : NULL;
    if (!buf || fread(buf, 1, (size_t)len, f) != (size_t)len) {
        free(buf);
        fclose(f);
        return false;
    }
    fclose(f);
    buf[len] = '\0';   // keymap is sent NUL-terminated
    srv->seat.keymap_fd = dnc_create_shared_file(srv, buf, (size_t)len + 1);
    srv->seat.keymap_size = (uint32_t)len + 1;
    free(buf);
    return srv->seat.keymap_fd >= 0;
}

static bool load_keymap(struct dnc_server *srv)
{
    return load_keymap_file(srv, srv->config.keymap_path);
}

// New layout (a hardware keyboard): every client gets the new keymap
static void replace_keymap(struct dnc_server *srv, const char *path)
{
    int old_fd = srv->seat.keymap_fd;
    uint32_t old_size = srv->seat.keymap_size;
    if (!load_keymap_file(srv, path) || srv->seat.keymap_fd < 0) {
        dnc_log(srv, DNC_LOG_WARN, "keymap %s not loaded, keeping the old one", path);
        srv->seat.keymap_fd = old_fd;
        srv->seat.keymap_size = old_size;
        return;
    }
    if (old_fd >= 0)
        close(old_fd);
    dnc_seat_keymap_changed(srv);
    dnc_log(srv, DNC_LOG_INFO, "keymap replaced by %s", path);
}

struct dnc_server *dnc_server_create(const struct dnc_config *config,
                                     const struct dnc_renderer *renderer,
                                     const struct dnc_callbacks *callbacks,
                                     void *callback_data)
{
    struct dnc_server *srv = calloc(1, sizeof(*srv));
    if (!srv)
        return NULL;
    srv->config = *config;
    if (srv->config.scale < 1)
        srv->config.scale = 1;
    if (srv->config.refresh_mhz <= 0)
        srv->config.refresh_mhz = 60000;
    srv->socket_path = strdup(config->socket_path);
    srv->config.socket_path = srv->socket_path;
    srv->renderer = renderer;
    if (callbacks)
        srv->callbacks = *callbacks;
    srv->callback_data = callback_data;
    srv->listen_fd = -1;
    srv->cmd_fd = -1;
    srv->next_toplevel_id = 1;
    wl_list_init(&srv->surfaces);
    wl_list_init(&srv->toplevels);
    wl_list_init(&srv->outputs);
    pthread_mutex_init(&srv->cmd_lock, NULL);
    pthread_mutex_init(&srv->stats_lock, NULL);

    if (!renderer->init(&srv->renderer_state)) {
        dnc_log(srv, DNC_LOG_ERROR, "renderer init failed");
        goto fail;
    }

    srv->display = wl_display_create();
    if (!srv->display)
        goto fail;
    srv->loop = wl_display_get_event_loop(srv->display);

    srv->listen_fd = bind_socket(srv->socket_path);
    if (srv->listen_fd < 0) {
        dnc_log(srv, DNC_LOG_ERROR, "cannot bind %s: %s", srv->socket_path,
                strerror(errno));
        goto fail;
    }
    if (wl_display_add_socket_fd(srv->display, srv->listen_fd) < 0)
        goto fail;
    srv->listen_fd = -1;   // owned by the display now

    srv->cmd_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (srv->cmd_fd < 0)
        goto fail;
    srv->cmd_source = wl_event_loop_add_fd(srv->loop, srv->cmd_fd,
                                           WL_EVENT_READABLE, handle_cmd_fd, srv);
    srv->hidden_frame_timer = wl_event_loop_add_timer(srv->loop,
                                                      handle_hidden_frames, srv);

    if (!load_keymap(srv))
        dnc_log(srv, DNC_LOG_WARN, "keymap could not be loaded");

    if (wl_display_init_shm(srv->display) < 0)
        goto fail;
    wl_display_add_shm_format(srv->display, WL_SHM_FORMAT_ABGR8888);
    wl_display_add_shm_format(srv->display, WL_SHM_FORMAT_XBGR8888);

    if (!dnc_compositor_init(srv) || !dnc_xdg_shell_init(srv) ||
        !dnc_dmabuf_init(srv) || !dnc_seat_init(srv) ||
        !dnc_output_init(srv) || !dnc_data_device_init(srv) ||
        !dnc_decoration_init(srv) || !dnc_text_input_init(srv) ||
        !dnc_xwayland_init(srv) || !dnc_constraints_init(srv))
        goto fail;

    dnc_log(srv, DNC_LOG_INFO, "listening on %s (scale %d)", srv->socket_path,
            srv->config.scale);
    return srv;

fail:
    dnc_server_destroy(srv);
    return NULL;
}

int dnc_server_run(struct dnc_server *srv)
{
    srv->running = true;
    while (srv->running) {
        wl_display_flush_clients(srv->display);
        if (wl_event_loop_dispatch(srv->loop, -1) < 0 && errno != EINTR) {
            dnc_log(srv, DNC_LOG_ERROR, "event loop: %s", strerror(errno));
            return -1;
        }
    }
    return 0;
}

void dnc_server_destroy(struct dnc_server *srv)
{
    if (!srv)
        return;
    if (srv->display) {
        wl_display_destroy_clients(srv->display);
        struct dnc_toplevel *tl, *tmp;
        wl_list_for_each_safe(tl, tmp, &srv->toplevels, link)
            window_destroy(srv, tl);
        if (srv->xwl_windows.next)
            dnc_xwayland_finish(srv);
        dnc_seat_finish(srv);
        wl_display_destroy(srv->display);
    }
    if (srv->listen_fd >= 0)
        close(srv->listen_fd);
    if (srv->cmd_fd >= 0)
        close(srv->cmd_fd);
    if (srv->renderer_state)
        srv->renderer->finish(srv->renderer_state);
    if (srv->socket_path) {
        unlink(srv->socket_path);
        free(srv->socket_path);
    }
    struct dnc_cmd *cmd = srv->cmd_head;
    while (cmd) {
        struct dnc_cmd *next = cmd->next;
        free(cmd);
        cmd = next;
    }
    pthread_mutex_destroy(&srv->cmd_lock);
    pthread_mutex_destroy(&srv->stats_lock);
    free(srv);
}
