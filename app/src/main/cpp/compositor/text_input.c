/*
 * zwp_text_input_manager_v3: tells the host when a client wants text entry
 * (a focused text field), so it can show its on-screen keyboard, and carries
 * the composed text back. Toolkits (GTK 3/4, Qt 6) use it on Wayland; key
 * events alone cannot express text from an Android input method.
 *
 * Surrounding text, content type and cursor rectangle are accepted and
 * ignored: the Android side runs the input method without an editor model.
 */
#include <stdlib.h>
#include <string.h>

#include "dnc_private.h"
#include "text-input-unstable-v3-server-protocol.h"

#define TEXT_INPUT_VERSION 1

struct text_input {
    struct wl_resource *resource;
    struct wl_list link;            // dnc_server.text_inputs
    struct dnc_server *server;
    bool entered;                   // enter sent for the focused surface
    bool pending_enabled, enabled;
    uint32_t commits;               // serial for done
};

static struct dnc_toplevel *focused_toplevel(struct dnc_server *srv)
{
    struct dnc_toplevel *tl;
    wl_list_for_each(tl, &srv->toplevels, link) {
        if (tl->xdg && tl->xdg->surface == srv->seat.keyboard_focus)
            return tl;
    }
    return NULL;
}

// The host keyboard follows the focused client's enabled text inputs
static void notify_host(struct dnc_server *srv)
{
    bool active = false;
    struct text_input *ti;
    wl_list_for_each(ti, &srv->text_inputs, link) {
        if (ti->entered && ti->enabled)
            active = true;
    }
    struct dnc_toplevel *tl = focused_toplevel(srv);
    uint32_t id = tl ? tl->id : 0;
    if (active == srv->text_input_active && id == srv->text_input_toplevel)
        return;
    // Focus moved while active: the old window must hide its keyboard first
    if (srv->text_input_active && srv->callbacks.text_input)
        srv->callbacks.text_input(srv->callback_data, srv->text_input_toplevel, false);
    srv->text_input_active = active;
    srv->text_input_toplevel = id;
    dnc_log(srv, DNC_LOG_INFO, "text input %s (%s)", active ? "on" : "off",
            tl && tl->app_id ? tl->app_id : "-");
    if (active && srv->callbacks.text_input)
        srv->callbacks.text_input(srv->callback_data, id, true);
}

void dnc_text_input_focus(struct dnc_server *srv, struct dnc_surface *old,
                          struct dnc_surface *new)
{
    struct text_input *ti;
    wl_list_for_each(ti, &srv->text_inputs, link) {
        struct wl_client *client = wl_resource_get_client(ti->resource);
        if (ti->entered && old) {
            zwp_text_input_v3_send_leave(ti->resource, old->resource);
            ti->entered = false;
            // The protocol resets the state on leave; clients enable again
            ti->enabled = ti->pending_enabled = false;
        }
        if (new && wl_resource_get_client(new->resource) == client) {
            zwp_text_input_v3_send_enter(ti->resource, new->resource);
            ti->entered = true;
        }
    }
    notify_host(srv);
}

void dnc_text_input_focus_lost(struct dnc_server *srv)
{
    struct text_input *ti;
    wl_list_for_each(ti, &srv->text_inputs, link)
        ti->entered = ti->enabled = ti->pending_enabled = false;
    notify_host(srv);
}

/* Host -> client */

static struct text_input *active_input(struct dnc_server *srv)
{
    struct text_input *ti;
    wl_list_for_each(ti, &srv->text_inputs, link) {
        if (ti->entered && ti->enabled)
            return ti;
    }
    return NULL;
}

bool dnc_text_input_commit(struct dnc_server *srv, const char *text)
{
    struct text_input *ti = active_input(srv);
    if (!ti)
        return false;
    zwp_text_input_v3_send_preedit_string(ti->resource, NULL, 0, 0);
    zwp_text_input_v3_send_commit_string(ti->resource, text);
    zwp_text_input_v3_send_done(ti->resource, ti->commits);
    return true;
}

bool dnc_text_input_preedit(struct dnc_server *srv, const char *text)
{
    struct text_input *ti = active_input(srv);
    if (!ti)
        return false;
    int32_t len = (int32_t)strlen(text);
    zwp_text_input_v3_send_preedit_string(ti->resource, len ? text : NULL, len, len);
    zwp_text_input_v3_send_done(ti->resource, ti->commits);
    return true;
}

/* zwp_text_input_v3 */

static void ti_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void ti_enable(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    struct text_input *ti = wl_resource_get_user_data(r);
    ti->pending_enabled = true;
}

static void ti_disable(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    struct text_input *ti = wl_resource_get_user_data(r);
    ti->pending_enabled = false;
}

static void ti_set_surrounding_text(struct wl_client *c, struct wl_resource *r,
                                    const char *text, int32_t cursor, int32_t anchor)
{
    (void)c; (void)r; (void)text; (void)cursor; (void)anchor;
}

static void ti_set_text_change_cause(struct wl_client *c, struct wl_resource *r,
                                     uint32_t cause)
{
    (void)c; (void)r; (void)cause;
}

static void ti_set_content_type(struct wl_client *c, struct wl_resource *r,
                                uint32_t hint, uint32_t purpose)
{
    (void)c; (void)r; (void)hint; (void)purpose;
}

static void ti_set_cursor_rectangle(struct wl_client *c, struct wl_resource *r,
                                    int32_t x, int32_t y, int32_t w, int32_t h)
{
    (void)c; (void)r; (void)x; (void)y; (void)w; (void)h;
}

static void ti_commit(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    struct text_input *ti = wl_resource_get_user_data(r);
    struct dnc_server *srv = ti->server;
    ti->commits++;
    // Clients commit on every cursor or content change; only a change of
    // the enabled state concerns the host keyboard
    ti->enabled = ti->pending_enabled && ti->entered;
    notify_host(srv);
}

static const struct zwp_text_input_v3_interface text_input_impl = {
    .destroy = ti_destroy,
    .enable = ti_enable,
    .disable = ti_disable,
    .set_surrounding_text = ti_set_surrounding_text,
    .set_text_change_cause = ti_set_text_change_cause,
    .set_content_type = ti_set_content_type,
    .set_cursor_rectangle = ti_set_cursor_rectangle,
    .commit = ti_commit,
};

static void ti_resource_destroy(struct wl_resource *r)
{
    struct text_input *ti = wl_resource_get_user_data(r);
    struct dnc_server *srv = ti->server;
    wl_list_remove(&ti->link);
    free(ti);
    notify_host(srv);
}

/* zwp_text_input_manager_v3 */

static void manager_destroy(struct wl_client *c, struct wl_resource *r)
{
    (void)c;
    wl_resource_destroy(r);
}

static void manager_get_text_input(struct wl_client *c, struct wl_resource *r,
                                   uint32_t id, struct wl_resource *seat)
{
    (void)seat;
    struct dnc_server *srv = wl_resource_get_user_data(r);
    struct text_input *ti = calloc(1, sizeof(*ti));
    if (!ti) {
        wl_client_post_no_memory(c);
        return;
    }
    ti->resource = wl_resource_create(c, &zwp_text_input_v3_interface,
                                      wl_resource_get_version(r), id);
    if (!ti->resource) {
        free(ti);
        wl_client_post_no_memory(c);
        return;
    }
    ti->server = srv;
    wl_list_insert(&srv->text_inputs, &ti->link);
    wl_resource_set_implementation(ti->resource, &text_input_impl, ti,
                                   ti_resource_destroy);
    // Created after the surface got keyboard focus: enter right away
    struct dnc_surface *focus = srv->seat.keyboard_focus;
    if (focus && wl_resource_get_client(focus->resource) == c) {
        zwp_text_input_v3_send_enter(ti->resource, focus->resource);
        ti->entered = true;
    }
}

static const struct zwp_text_input_manager_v3_interface manager_impl = {
    .destroy = manager_destroy,
    .get_text_input = manager_get_text_input,
};

static void bind_manager(struct wl_client *c, void *data, uint32_t version,
                         uint32_t id)
{
    struct wl_resource *r = wl_resource_create(
        c, &zwp_text_input_manager_v3_interface, (int)version, id);
    if (!r) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(r, &manager_impl, data, NULL);
}

bool dnc_text_input_init(struct dnc_server *srv)
{
    wl_list_init(&srv->text_inputs);
    return wl_global_create(srv->display, &zwp_text_input_manager_v3_interface,
                            TEXT_INPUT_VERSION, srv, bind_manager) != NULL;
}
