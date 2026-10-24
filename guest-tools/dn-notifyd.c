/*
 * dn-notifyd: relays the guest's desktop notifications
 * (org.freedesktop.Notifications), media players (MPRIS) and text fields
 * of X11 programs (as a Fcitx 5 input method) to the DroidNative app, and
 * the app's answers (actions, dismissals, media buttons, keyboard text)
 * back. One JSON object per line over the Unix socket
 * $XDG_RUNTIME_DIR/bridge.sock, served by the app (Bridge.kt).
 * Protocol: docs/bridge-protocol.md. Started by session.sh.
 */
#define _GNU_SOURCE
#include <gio/gio.h>
#include <gio/gunixsocketaddress.h>
#include <json-glib/json-glib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define PROTOCOL_VERSION 1
#define MPRIS_PREFIX "org.mpris.MediaPlayer2."
#define MPRIS_PATH "/org/mpris/MediaPlayer2"

static GDBusConnection *bus;
static GSocketConnection *bridge;
static GDataInputStream *bridge_in;
static GCancellable *bridge_cancel;
static char *socket_path;
static char *image_dir;
static GHashTable *players;      // bus name -> struct player
static GHashTable *expiries;     // notification id -> timeout source id
static guint32 next_id = 1;

static void bridge_connect_later(void);
static void player_send(const char *name);
static void ics_drop_owner(const char *owner);

/* Bridge to the app */

static void send_json(JsonBuilder *b)
{
    JsonNode *root = json_builder_get_root(b);
    if (bridge) {
        JsonGenerator *gen = json_generator_new();
        json_generator_set_root(gen, root);
        gsize len;
        char *text = json_generator_to_data(gen, &len);
        GOutputStream *out = g_io_stream_get_output_stream(G_IO_STREAM(bridge));
        GError *err = NULL;
        if (!g_output_stream_write_all(out, text, len, NULL, NULL, &err) ||
            !g_output_stream_write_all(out, "\n", 1, NULL, NULL, &err)) {
            g_printerr("dn-notifyd: bridge write: %s\n", err->message);
            g_error_free(err);
        }
        g_free(text);
        g_object_unref(gen);
    }
    json_node_unref(root);
    g_object_unref(b);
}

static JsonBuilder *message(const char *type)
{
    JsonBuilder *b = json_builder_new();
    json_builder_begin_object(b);
    json_builder_set_member_name(b, "type");
    json_builder_add_string_value(b, type);
    return b;
}

static void member_string(JsonBuilder *b, const char *name, const char *value)
{
    json_builder_set_member_name(b, name);
    json_builder_add_string_value(b, value ? value : "");
}

static void member_int(JsonBuilder *b, const char *name, gint64 value)
{
    json_builder_set_member_name(b, name);
    json_builder_add_int_value(b, value);
}

static void member_bool(JsonBuilder *b, const char *name, gboolean value)
{
    json_builder_set_member_name(b, name);
    json_builder_add_boolean_value(b, value);
}

static void send_end(JsonBuilder *b)
{
    json_builder_end_object(b);
    send_json(b);
}

/* Notifications */

static const char notify_xml[] =
    "<node><interface name='org.freedesktop.Notifications'>"
    " <method name='GetCapabilities'><arg type='as' direction='out'/></method>"
    " <method name='Notify'>"
    "  <arg type='s' direction='in'/><arg type='u' direction='in'/>"
    "  <arg type='s' direction='in'/><arg type='s' direction='in'/>"
    "  <arg type='s' direction='in'/><arg type='as' direction='in'/>"
    "  <arg type='a{sv}' direction='in'/><arg type='i' direction='in'/>"
    "  <arg type='u' direction='out'/></method>"
    " <method name='CloseNotification'><arg type='u' direction='in'/></method>"
    " <method name='GetServerInformation'>"
    "  <arg type='s' direction='out'/><arg type='s' direction='out'/>"
    "  <arg type='s' direction='out'/><arg type='s' direction='out'/></method>"
    " <signal name='NotificationClosed'><arg type='u'/><arg type='u'/></signal>"
    " <signal name='ActionInvoked'><arg type='u'/><arg type='s'/></signal>"
    "</interface></node>";

// Reasons of NotificationClosed
enum { CLOSED_EXPIRED = 1, CLOSED_DISMISSED = 2, CLOSED_BY_CALL = 3 };

static void emit_closed(guint32 id, guint32 reason)
{
    g_hash_table_remove(expiries, GUINT_TO_POINTER(id));
    char path[512];
    snprintf(path, sizeof path, "%s/%u.rgba", image_dir, id);
    unlink(path);
    g_dbus_connection_emit_signal(bus, NULL, "/org/freedesktop/Notifications",
        "org.freedesktop.Notifications", "NotificationClosed",
        g_variant_new("(uu)", id, reason), NULL);
}

static void send_close(guint32 id)
{
    JsonBuilder *b = message("close");
    member_int(b, "id", id);
    send_end(b);
}

static gboolean expire(gpointer data)
{
    guint32 id = GPOINTER_TO_UINT(data);
    g_hash_table_steal(expiries, data);   // the source ends by returning FALSE
    send_close(id);
    emit_closed(id, CLOSED_EXPIRED);
    return G_SOURCE_REMOVE;
}

static void remove_source(gpointer data)
{
    g_source_remove(GPOINTER_TO_UINT(data));
}

static guint32 sender_pid(const char *sender)
{
    GVariant *r = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
        "org.freedesktop.DBus", "GetConnectionUnixProcessID", g_variant_new("(s)", sender),
        G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, 1000, NULL, NULL);
    guint32 pid = 0;
    if (r) {
        g_variant_get(r, "(u)", &pid);
        g_variant_unref(r);
    }
    return pid;
}

// image-data (iiibiiay): raw pixels into a file the app reads
static void add_image(JsonBuilder *b, guint32 id, GVariant *image)
{
    gint32 w, h, stride, bits, channels;
    gboolean alpha;
    GVariant *data;
    g_variant_get(image, "(iiibii@ay)", &w, &h, &stride, &alpha, &bits, &channels, &data);
    gsize len;
    const guint8 *pixels = g_variant_get_fixed_array(data, &len, 1);
    if (bits == 8 && (channels == 3 || channels == 4) && w > 0 && h > 0 &&
        (gsize)stride * (h - 1) + (gsize)w * channels <= len) {
        char path[512];
        snprintf(path, sizeof path, "%s/%u.rgba", image_dir, id);
        if (g_file_set_contents(path, (const char *)pixels, len, NULL)) {
            json_builder_set_member_name(b, "image");
            json_builder_begin_object(b);
            member_string(b, "file", path);
            member_int(b, "width", w);
            member_int(b, "height", h);
            member_int(b, "rowstride", stride);
            member_int(b, "channels", channels);
            json_builder_end_object(b);
        }
    }
    g_variant_unref(data);
}

static guint32 notify(const char *sender, const char *app, guint32 replaces, const char *icon,
                      const char *summary, const char *body, const char **actions,
                      GVariant *hints, gint32 timeout)
{
    guint32 id = replaces ? replaces : next_id++;
    if (next_id == 0) next_id = 1;
    g_hash_table_remove(expiries, GUINT_TO_POINTER(id));

    JsonBuilder *b = message("notify");
    member_int(b, "id", id);
    member_int(b, "pid", sender_pid(sender));
    member_string(b, "app", app);
    member_string(b, "icon", icon);
    member_string(b, "title", summary);
    member_string(b, "body", body);

    const char *s;
    gint64 urgency = 1;
    gboolean flag;
    GVariant *v;
    // A byte by the specification; some senders use an int
    if ((v = g_variant_lookup_value(hints, "urgency", NULL))) {
        if (g_variant_is_of_type(v, G_VARIANT_TYPE_BYTE)) urgency = g_variant_get_byte(v);
        else if (g_variant_is_of_type(v, G_VARIANT_TYPE_INT32)) urgency = g_variant_get_int32(v);
        else if (g_variant_is_of_type(v, G_VARIANT_TYPE_UINT32)) urgency = g_variant_get_uint32(v);
        g_variant_unref(v);
    }
    member_int(b, "urgency", urgency);
    member_string(b, "desktop", g_variant_lookup(hints, "desktop-entry", "&s", &s) ? s : "");
    member_string(b, "category", g_variant_lookup(hints, "category", "&s", &s) ? s : "");
    if (g_variant_lookup(hints, "image-path", "&s", &s) || g_variant_lookup(hints, "image_path", "&s", &s))
        member_string(b, "imagePath", s);
    member_bool(b, "transient", g_variant_lookup(hints, "transient", "b", &flag) && flag);
    member_bool(b, "resident", g_variant_lookup(hints, "resident", "b", &flag) && flag);
    if ((v = g_variant_lookup_value(hints, "image-data", G_VARIANT_TYPE("(iiibiiay)"))) ||
        (v = g_variant_lookup_value(hints, "image_data", G_VARIANT_TYPE("(iiibiiay)"))) ||
        (v = g_variant_lookup_value(hints, "icon_data", G_VARIANT_TYPE("(iiibiiay)")))) {
        add_image(b, id, v);
        g_variant_unref(v);
    }

    json_builder_set_member_name(b, "actions");
    json_builder_begin_array(b);
    for (int i = 0; actions[i] && actions[i + 1]; i += 2) {
        json_builder_begin_array(b);
        json_builder_add_string_value(b, actions[i]);
        json_builder_add_string_value(b, actions[i + 1]);
        json_builder_end_array(b);
    }
    json_builder_end_array(b);
    send_end(b);

    // -1: server default, which is to stay like other Android notifications
    if (timeout > 0)
        g_hash_table_insert(expiries, GUINT_TO_POINTER(id),
            GUINT_TO_POINTER(g_timeout_add(timeout, expire, GUINT_TO_POINTER(id))));
    return id;
}

static void notify_call(GDBusConnection *c, const char *sender, const char *path, const char *iface,
                        const char *method, GVariant *params, GDBusMethodInvocation *inv, gpointer data)
{
    (void)c; (void)path; (void)iface; (void)data;
    if (!strcmp(method, "GetCapabilities")) {
        const char *caps[] = { "actions", "body", "body-markup", "icon-static", "persistence", NULL };
        g_dbus_method_invocation_return_value(inv, g_variant_new("(^as)", caps));
    } else if (!strcmp(method, "GetServerInformation")) {
        g_dbus_method_invocation_return_value(inv,
            g_variant_new("(ssss)", "DroidNative", "DroidNative", "1", "1.2"));
    } else if (!strcmp(method, "CloseNotification")) {
        guint32 id;
        g_variant_get(params, "(u)", &id);
        send_close(id);
        emit_closed(id, CLOSED_BY_CALL);
        g_dbus_method_invocation_return_value(inv, NULL);
    } else if (!strcmp(method, "Notify")) {
        const char *app, *icon, *summary, *body;
        const char **actions;
        guint32 replaces;
        gint32 timeout;
        GVariant *hints;
        g_variant_get(params, "(&su&s&s&s^a&s@a{sv}i)", &app, &replaces, &icon, &summary, &body,
            &actions, &hints, &timeout);
        guint32 id = notify(sender, app, replaces, icon, summary, body, actions, hints, timeout);
        g_free(actions);
        g_variant_unref(hints);
        g_dbus_method_invocation_return_value(inv, g_variant_new("(u)", id));
    }
}

static const GDBusInterfaceVTable notify_vtable = { notify_call, NULL, NULL, { 0 } };

/* Media players (MPRIS) */

struct player {
    char *name;            // org.mpris.MediaPlayer2.<x>
    GDBusProxy *root;      // org.mpris.MediaPlayer2: Identity, DesktopEntry, Raise
    GDBusProxy *ctl;       // org.mpris.MediaPlayer2.Player
    guint32 pid;
    guint pending;         // idle source coalescing changes
};

static void player_free(gpointer data)
{
    struct player *p = data;
    if (p->pending) g_source_remove(p->pending);
    if (p->root) g_object_unref(p->root);
    if (p->ctl) g_object_unref(p->ctl);
    g_free(p->name);
    g_free(p);
}

static const char *prop_string(GDBusProxy *proxy, const char *name, GVariant **hold)
{
    *hold = proxy ? g_dbus_proxy_get_cached_property(proxy, name) : NULL;
    return *hold && g_variant_is_of_type(*hold, G_VARIANT_TYPE_STRING) ? g_variant_get_string(*hold, NULL) : "";
}

static gboolean prop_bool(GDBusProxy *proxy, const char *name)
{
    GVariant *v = g_dbus_proxy_get_cached_property(proxy, name);
    gboolean r = v && g_variant_is_of_type(v, G_VARIANT_TYPE_BOOLEAN) && g_variant_get_boolean(v);
    if (v) g_variant_unref(v);
    return r;
}

// Position is not announced by players: read it fresh
static gint64 player_position(struct player *p)
{
    GVariant *r = g_dbus_connection_call_sync(bus, p->name, MPRIS_PATH,
        "org.freedesktop.DBus.Properties", "Get",
        g_variant_new("(ss)", "org.mpris.MediaPlayer2.Player", "Position"),
        G_VARIANT_TYPE("(v)"), G_DBUS_CALL_FLAGS_NONE, 500, NULL, NULL);
    gint64 pos = -1;
    if (r) {
        GVariant *v;
        g_variant_get(r, "(v)", &v);
        if (g_variant_is_of_type(v, G_VARIANT_TYPE_INT64)) pos = g_variant_get_int64(v);
        g_variant_unref(v);
        g_variant_unref(r);
    }
    return pos;
}

static void add_metadata(JsonBuilder *b, GDBusProxy *ctl)
{
    GVariant *meta = g_dbus_proxy_get_cached_property(ctl, "Metadata");
    const char *s = "";
    char *trackid = NULL;
    gint64 length = -1;
    char *artist = NULL;
    if (meta && g_variant_is_of_type(meta, G_VARIANT_TYPE_VARDICT)) {
        GVariant *v;
        if ((v = g_variant_lookup_value(meta, "mpris:trackid", NULL))) {
            if (g_variant_is_of_type(v, G_VARIANT_TYPE_OBJECT_PATH) || g_variant_is_of_type(v, G_VARIANT_TYPE_STRING))
                trackid = g_variant_dup_string(v, NULL);
            g_variant_unref(v);
        }
        if ((v = g_variant_lookup_value(meta, "mpris:length", NULL))) {
            if (g_variant_is_of_type(v, G_VARIANT_TYPE_INT64)) length = g_variant_get_int64(v);
            else if (g_variant_is_of_type(v, G_VARIANT_TYPE_UINT64)) length = (gint64)g_variant_get_uint64(v);
            g_variant_unref(v);
        }
        if ((v = g_variant_lookup_value(meta, "xesam:artist", G_VARIANT_TYPE_STRING_ARRAY))) {
            const char **list = g_variant_get_strv(v, NULL);
            artist = g_strjoinv(", ", (char **)list);
            g_free(list);
            g_variant_unref(v);
        }
    }
    member_string(b, "trackid", trackid);
    g_free(trackid);
    member_int(b, "length", length);
    member_string(b, "artist", artist);
    g_free(artist);
    const char *keys[][2] = { { "xesam:title", "title" }, { "xesam:album", "album" }, { "mpris:artUrl", "art" } };
    for (size_t i = 0; i < G_N_ELEMENTS(keys); i++) {
        s = "";
        if (meta && g_variant_is_of_type(meta, G_VARIANT_TYPE_VARDICT))
            g_variant_lookup(meta, keys[i][0], "&s", &s);
        member_string(b, keys[i][1], s);
    }
    if (meta) g_variant_unref(meta);
}

static gboolean player_send_now(gpointer data)
{
    struct player *p = data;
    p->pending = 0;
    GVariant *h1, *h2, *h3;
    JsonBuilder *b = message("player");
    member_string(b, "name", p->name);
    member_int(b, "pid", p->pid);
    member_string(b, "identity", prop_string(p->root, "Identity", &h1));
    member_string(b, "desktop", prop_string(p->root, "DesktopEntry", &h2));
    member_string(b, "status", prop_string(p->ctl, "PlaybackStatus", &h3));
    if (h1) g_variant_unref(h1);
    if (h2) g_variant_unref(h2);
    if (h3) g_variant_unref(h3);
    add_metadata(b, p->ctl);
    member_int(b, "position", player_position(p));
    GVariant *rate = g_dbus_proxy_get_cached_property(p->ctl, "Rate");
    json_builder_set_member_name(b, "rate");
    json_builder_add_double_value(b, rate && g_variant_is_of_type(rate, G_VARIANT_TYPE_DOUBLE)
        ? g_variant_get_double(rate) : 1.0);
    if (rate) g_variant_unref(rate);
    member_bool(b, "canPlay", prop_bool(p->ctl, "CanPlay"));
    member_bool(b, "canPause", prop_bool(p->ctl, "CanPause"));
    member_bool(b, "canNext", prop_bool(p->ctl, "CanGoNext"));
    member_bool(b, "canPrevious", prop_bool(p->ctl, "CanGoPrevious"));
    member_bool(b, "canSeek", prop_bool(p->ctl, "CanSeek"));
    send_end(b);
    return G_SOURCE_REMOVE;
}

static void player_send(const char *name)
{
    struct player *p = g_hash_table_lookup(players, name);
    // Players change several properties at once: send them together
    if (p && !p->pending) p->pending = g_timeout_add(50, player_send_now, p);
}

static void player_changed(GDBusProxy *proxy, GVariant *changed, GStrv invalidated, gpointer data)
{
    (void)proxy; (void)changed; (void)invalidated;
    player_send(data);
}

static void player_signal(GDBusProxy *proxy, const char *sender, const char *signal, GVariant *params, gpointer data)
{
    (void)proxy; (void)sender; (void)params;
    if (!strcmp(signal, "Seeked")) player_send(data);
}

static void player_add(const char *name, const char *owner)
{
    if (g_hash_table_contains(players, name)) return;
    struct player *p = g_new0(struct player, 1);
    p->name = g_strdup(name);
    p->pid = sender_pid(owner);
    GDBusProxyFlags flags = G_DBUS_PROXY_FLAGS_GET_INVALIDATED_PROPERTIES | G_DBUS_PROXY_FLAGS_DO_NOT_AUTO_START;
    p->root = g_dbus_proxy_new_sync(bus, flags, NULL, name, MPRIS_PATH, "org.mpris.MediaPlayer2", NULL, NULL);
    p->ctl = g_dbus_proxy_new_sync(bus, flags, NULL, name, MPRIS_PATH, "org.mpris.MediaPlayer2.Player", NULL, NULL);
    if (!p->ctl) {
        player_free(p);
        return;
    }
    g_hash_table_insert(players, p->name, p);
    // The handlers get the name owned by the table entry
    g_signal_connect(p->ctl, "g-properties-changed", G_CALLBACK(player_changed), p->name);
    g_signal_connect(p->ctl, "g-signal", G_CALLBACK(player_signal), p->name);
    if (p->root) g_signal_connect(p->root, "g-properties-changed", G_CALLBACK(player_changed), p->name);
    player_send(name);
}

static void player_remove(const char *name)
{
    if (!g_hash_table_remove(players, name)) return;
    JsonBuilder *b = message("player-gone");
    member_string(b, "name", name);
    send_end(b);
}

static void name_owner_changed(GDBusConnection *c, const char *sender, const char *path, const char *iface,
                               const char *signal, GVariant *params, gpointer data)
{
    (void)c; (void)sender; (void)path; (void)iface; (void)signal; (void)data;
    const char *name, *old, *new;
    g_variant_get(params, "(&s&s&s)", &name, &old, &new);
    if (name[0] == ':' && *old && !*new) ics_drop_owner(name);
    if (!g_str_has_prefix(name, MPRIS_PREFIX)) return;
    if (*old) player_remove(name);
    if (*new) player_add(name, new);
}

static void players_scan(void)
{
    GVariant *r = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
        "org.freedesktop.DBus", "ListNames", NULL, G_VARIANT_TYPE("(as)"), G_DBUS_CALL_FLAGS_NONE,
        2000, NULL, NULL);
    if (!r) return;
    GVariantIter *it;
    const char *name;
    g_variant_get(r, "(as)", &it);
    while (g_variant_iter_next(it, "&s", &name)) {
        if (!g_str_has_prefix(name, MPRIS_PREFIX)) continue;
        GVariant *o = g_dbus_connection_call_sync(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
            "org.freedesktop.DBus", "GetNameOwner", g_variant_new("(s)", name), G_VARIANT_TYPE("(s)"),
            G_DBUS_CALL_FLAGS_NONE, 1000, NULL, NULL);
        if (o) {
            const char *owner;
            g_variant_get(o, "(&s)", &owner);
            player_add(name, owner);
            g_variant_unref(o);
        }
    }
    g_variant_iter_free(it);
    g_variant_unref(r);
}

static void player_call(const char *name, const char *method, GVariant *params)
{
    struct player *p = g_hash_table_lookup(players, name);
    if (!p) {
        if (params) g_variant_unref(g_variant_ref_sink(params));
        return;
    }
    GDBusProxy *proxy = !strcmp(method, "Raise") ? p->root : p->ctl;
    if (proxy) g_dbus_proxy_call(proxy, method, params, G_DBUS_CALL_FLAGS_NONE, 2000, NULL, NULL, NULL);
    else if (params) g_variant_unref(g_variant_ref_sink(params));
}

/* Input method (Fcitx 5 D-Bus frontend) */

/*
 * X11 programs have no text-input protocol, so the compositor cannot tell
 * when one of their text fields is active. Toolkits that speak Fcitx 5's
 * D-Bus protocol (GTK through fcitx5-gtk, which dn-xwm selects for X11 via
 * XSETTINGS; SDL with SDL_IM_MODULE=fcitx) report it here: FocusIn and
 * FocusOut become "im-focus", and the app's keyboard text comes back as
 * CommitString. Keys are never consumed (ProcessKeyEvent answers false).
 */

#define IM_PATH "/org/freedesktop/portal/inputmethod"
#define IC_PREFIX "/org/freedesktop/portal/inputcontext/"
#define IC_IFACE "org.fcitx.Fcitx.InputContext1"

static const char im_xml[] =
    "<node><interface name='org.fcitx.Fcitx.InputMethod1'>"
    " <method name='CreateInputContext'><arg type='a(ss)' direction='in'/>"
    "  <arg type='o' direction='out'/><arg type='ay' direction='out'/></method>"
    "</interface></node>";

static const char ic_xml[] =
    "<node><interface name='" IC_IFACE "'>"
    " <method name='FocusIn'/><method name='FocusOut'/><method name='Reset'/>"
    " <method name='DestroyIC'/>"
    " <method name='SetCursorRect'><arg type='i' direction='in'/><arg type='i' direction='in'/>"
    "  <arg type='i' direction='in'/><arg type='i' direction='in'/></method>"
    " <method name='SetCursorRectV2'><arg type='i' direction='in'/><arg type='i' direction='in'/>"
    "  <arg type='i' direction='in'/><arg type='i' direction='in'/><arg type='d' direction='in'/></method>"
    " <method name='SetCapability'><arg type='t' direction='in'/></method>"
    " <method name='SetSurroundingText'><arg type='s' direction='in'/><arg type='u' direction='in'/>"
    "  <arg type='u' direction='in'/></method>"
    " <method name='SetSurroundingTextPosition'><arg type='u' direction='in'/>"
    "  <arg type='u' direction='in'/></method>"
    " <method name='ProcessKeyEvent'><arg type='u' direction='in'/><arg type='u' direction='in'/>"
    "  <arg type='u' direction='in'/><arg type='b' direction='in'/><arg type='u' direction='in'/>"
    "  <arg type='b' direction='out'/></method>"
    " <method name='PrevPage'/><method name='NextPage'/>"
    " <method name='SelectCandidate'><arg type='i' direction='in'/></method>"
    " <signal name='CommitString'><arg type='s'/></signal>"
    " <signal name='UpdateFormattedPreedit'><arg type='a(si)'/><arg type='i'/></signal>"
    "</interface></node>";

struct ic {
    char *path;
    char *owner;           // unique bus name of the client
    guint reg;             // object registration
    guint32 pid;
};

static GDBusInterfaceInfo *ic_info;
static GHashTable *ics;    // path -> struct ic
static struct ic *focused; // context with the active text field
static guint32 next_ic = 1;

static void ic_free(gpointer data)
{
    struct ic *c = data;
    g_dbus_connection_unregister_object(bus, c->reg);
    g_free(c->path);
    g_free(c->owner);
    g_free(c);
}

static void send_im_focus(struct ic *c, gboolean active)
{
    JsonBuilder *b = message("im-focus");
    member_bool(b, "active", active);
    member_int(b, "pid", c->pid);
    send_end(b);
}

static void ic_unfocus(struct ic *c)
{
    if (focused != c) return;
    focused = NULL;
    send_im_focus(c, FALSE);
}

static void ic_signal(struct ic *c, const char *name, GVariant *params)
{
    g_dbus_connection_emit_signal(bus, c->owner, c->path, IC_IFACE, name, params, NULL);
}

static void ic_call(GDBusConnection *conn, const char *sender, const char *path, const char *iface,
                    const char *method, GVariant *params, GDBusMethodInvocation *inv, gpointer data)
{
    (void)conn; (void)sender; (void)iface; (void)params; (void)data;
    struct ic *c = g_hash_table_lookup(ics, path);
    if (!c) {
        g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownObject", path);
        return;
    }
    if (!strcmp(method, "ProcessKeyEvent")) {
        g_dbus_method_invocation_return_value(inv, g_variant_new("(b)", FALSE));
        return;
    }
    g_dbus_method_invocation_return_value(inv, NULL);
    if (!strcmp(method, "FocusIn")) {
        if (focused == c) return;
        focused = c;
        send_im_focus(c, TRUE);
    } else if (!strcmp(method, "FocusOut")) {
        ic_unfocus(c);
    } else if (!strcmp(method, "DestroyIC")) {
        ic_unfocus(c);
        g_hash_table_remove(ics, path);
    }
}

static const GDBusInterfaceVTable ic_vtable = { ic_call, NULL, NULL, { 0 } };

static void im_call(GDBusConnection *conn, const char *sender, const char *path, const char *iface,
                    const char *method, GVariant *params, GDBusMethodInvocation *inv, gpointer data)
{
    (void)conn; (void)path; (void)iface; (void)params; (void)data;
    if (strcmp(method, "CreateInputContext")) return;
    struct ic *c = g_new0(struct ic, 1);
    c->path = g_strdup_printf(IC_PREFIX "%u", next_ic++);
    c->owner = g_strdup(sender);
    c->pid = sender_pid(sender);
    GError *err = NULL;
    c->reg = g_dbus_connection_register_object(bus, c->path, ic_info, &ic_vtable, NULL, NULL, &err);
    if (!c->reg) {
        g_dbus_method_invocation_return_gerror(inv, err);
        g_error_free(err);
        g_free(c->path);
        g_free(c->owner);
        g_free(c);
        return;
    }
    g_hash_table_insert(ics, c->path, c);
    guint8 uuid[16];
    for (int i = 0; i < 16; i++) uuid[i] = (guint8)g_random_int_range(0, 256);
    g_dbus_method_invocation_return_value(inv, g_variant_new("(o@ay)", c->path,
        g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, uuid, 16, 1)));
}

static const GDBusInterfaceVTable im_vtable = { im_call, NULL, NULL, { 0 } };

// A client left the bus: its contexts go with it
static void ics_drop_owner(const char *owner)
{
    GHashTableIter it;
    gpointer value;
    if (!ics) return;
    g_hash_table_iter_init(&it, ics);
    while (g_hash_table_iter_next(&it, NULL, &value)) {
        struct ic *c = value;
        if (strcmp(c->owner, owner)) continue;
        ic_unfocus(c);
        g_hash_table_iter_remove(&it);
    }
}

static void im_text(const char *text, gboolean preedit)
{
    if (!focused) return;
    // Preedit: one underlined segment (fcitx TextFormatFlag Underline),
    // cursor at its end; a commit clears it first
    GVariantBuilder segs;
    g_variant_builder_init(&segs, G_VARIANT_TYPE("a(si)"));
    if (preedit && *text) g_variant_builder_add(&segs, "(si)", text, 1 << 3);
    ic_signal(focused, "UpdateFormattedPreedit",
        g_variant_new("(a(si)i)", &segs, preedit ? (gint32)strlen(text) : 0));
    if (!preedit) ic_signal(focused, "CommitString", g_variant_new("(s)", text));
}

static void im_setup(void)
{
    ics = g_hash_table_new_full(g_str_hash, g_str_equal, NULL, ic_free);
    GDBusNodeInfo *ic_node = g_dbus_node_info_new_for_xml(ic_xml, NULL);
    ic_info = g_dbus_interface_info_ref(ic_node->interfaces[0]);
    g_dbus_node_info_unref(ic_node);
    GDBusNodeInfo *im_node = g_dbus_node_info_new_for_xml(im_xml, NULL);
    g_dbus_connection_register_object(bus, IM_PATH, im_node->interfaces[0], &im_vtable, NULL, NULL, NULL);
    g_dbus_node_info_unref(im_node);
    // Not REPLACE: a real fcitx5 the user started keeps its name
    g_bus_own_name_on_connection(bus, "org.fcitx.Fcitx5", G_BUS_NAME_OWNER_FLAGS_NONE,
        NULL, NULL, NULL, NULL);
}

/* Messages from the app */

static void handle(const char *line)
{
    JsonParser *parser = json_parser_new();
    if (!json_parser_load_from_data(parser, line, -1, NULL) ||
        !JSON_NODE_HOLDS_OBJECT(json_parser_get_root(parser))) {
        g_printerr("dn-notifyd: bad message: %s\n", line);
        g_object_unref(parser);
        return;
    }
    JsonObject *o = json_node_get_object(json_parser_get_root(parser));
    const char *type = json_object_get_string_member_with_default(o, "type", "");
    guint32 id = (guint32)json_object_get_int_member_with_default(o, "id", 0);
    const char *name = json_object_get_string_member_with_default(o, "name", "");

    if (!strcmp(type, "action")) {
        const char *key = json_object_get_string_member_with_default(o, "key", "default");
        g_dbus_connection_emit_signal(bus, NULL, "/org/freedesktop/Notifications",
            "org.freedesktop.Notifications", "ActionInvoked", g_variant_new("(us)", id, key), NULL);
    } else if (!strcmp(type, "closed")) {
        emit_closed(id, CLOSED_DISMISSED);
    } else if (!strcmp(type, "media")) {
        static const char *const methods[][2] = {
            { "play", "Play" }, { "pause", "Pause" }, { "playpause", "PlayPause" }, { "stop", "Stop" },
            { "next", "Next" }, { "previous", "Previous" }, { "raise", "Raise" } };
        const char *cmd = json_object_get_string_member_with_default(o, "cmd", "");
        for (size_t i = 0; i < G_N_ELEMENTS(methods); i++)
            if (!strcmp(cmd, methods[i][0])) player_call(name, methods[i][1], NULL);
    } else if (!strcmp(type, "seek")) {
        const char *track = json_object_get_string_member_with_default(o, "trackid", "");
        gint64 pos = json_object_get_int_member_with_default(o, "position", 0);
        if (g_variant_is_object_path(track))
            player_call(name, "SetPosition", g_variant_new("(ox)", track, pos));
        player_send(name);
    } else if (!strcmp(type, "im-commit") || !strcmp(type, "im-preedit")) {
        im_text(json_object_get_string_member_with_default(o, "text", ""), type[3] == 'p');
    }
    g_object_unref(parser);
}

static void bridge_lost(void)
{
    if (!bridge) return;
    g_cancellable_cancel(bridge_cancel);
    g_clear_object(&bridge_cancel);
    g_clear_object(&bridge_in);
    g_clear_object(&bridge);
    bridge_connect_later();
}

static void bridge_read(GObject *src, GAsyncResult *res, gpointer data)
{
    (void)data;
    GError *err = NULL;
    gsize len;
    char *line = g_data_input_stream_read_line_finish_utf8(G_DATA_INPUT_STREAM(src), res, &len, &err);
    if (!line) {
        if (err && g_error_matches(err, G_IO_ERROR, G_IO_ERROR_CANCELLED)) {
            g_error_free(err);
            return;
        }
        g_printerr("dn-notifyd: bridge closed%s%s\n", err ? ": " : "", err ? err->message : "");
        g_clear_error(&err);
        bridge_lost();
        return;
    }
    if (len) handle(line);
    g_free(line);
    g_data_input_stream_read_line_async(bridge_in, G_PRIORITY_DEFAULT, bridge_cancel, bridge_read, NULL);
}

static gboolean bridge_connect(gpointer data)
{
    (void)data;
    GSocketClient *client = g_socket_client_new();
    GSocketAddress *addr = g_unix_socket_address_new(socket_path);
    bridge = g_socket_client_connect(client, G_SOCKET_CONNECTABLE(addr), NULL, NULL);
    g_object_unref(addr);
    g_object_unref(client);
    if (!bridge) return G_SOURCE_CONTINUE;   // the app is not listening yet: retry

    bridge_cancel = g_cancellable_new();
    bridge_in = g_data_input_stream_new(g_io_stream_get_input_stream(G_IO_STREAM(bridge)));
    g_data_input_stream_read_line_async(bridge_in, G_PRIORITY_DEFAULT, bridge_cancel, bridge_read, NULL);
    JsonBuilder *b = message("hello");
    member_int(b, "version", PROTOCOL_VERSION);
    send_end(b);
    // A restarted app learns the players again
    GHashTableIter it;
    gpointer key;
    g_hash_table_iter_init(&it, players);
    while (g_hash_table_iter_next(&it, &key, NULL)) player_send(key);
    return G_SOURCE_REMOVE;
}

static void bridge_connect_later(void)
{
    g_timeout_add_seconds(1, bridge_connect, NULL);
}

/* Main */

static void name_lost(GDBusConnection *c, const char *name, gpointer data)
{
    (void)c; (void)data;
    g_printerr("dn-notifyd: could not own %s\n", name);
}

int main(void)
{
    const char *run = g_getenv("XDG_RUNTIME_DIR");
    if (!run) run = "/run/droidnative";
    socket_path = g_build_filename(run, "bridge.sock", NULL);
    image_dir = g_build_filename(run, "notify", NULL);
    g_mkdir_with_parents(image_dir, 0700);

    GError *err = NULL;
    bus = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &err);
    if (!bus) {
        g_printerr("dn-notifyd: session bus: %s\n", err->message);
        return 1;
    }
    g_dbus_connection_set_exit_on_close(bus, TRUE);
    players = g_hash_table_new_full(g_str_hash, g_str_equal, NULL, player_free);
    expiries = g_hash_table_new_full(NULL, NULL, NULL, remove_source);

    GDBusNodeInfo *info = g_dbus_node_info_new_for_xml(notify_xml, NULL);
    g_dbus_connection_register_object(bus, "/org/freedesktop/Notifications", info->interfaces[0],
        &notify_vtable, NULL, NULL, &err);
    // Replaces a notification daemon the bus may have started
    g_bus_own_name_on_connection(bus, "org.freedesktop.Notifications", G_BUS_NAME_OWNER_FLAGS_REPLACE,
        NULL, name_lost, NULL, NULL);
    g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus",
        "NameOwnerChanged", "/org/freedesktop/DBus", NULL, G_DBUS_SIGNAL_FLAGS_NONE,
        name_owner_changed, NULL, NULL);
    players_scan();
    im_setup();
    if (bridge_connect(NULL) == G_SOURCE_CONTINUE) g_timeout_add_seconds(1, bridge_connect, NULL);

    g_main_loop_run(g_main_loop_new(NULL, FALSE));
    return 0;
}
