#!/usr/bin/env python3
"""Minimal MPRIS player for tests/host/notify/run.sh (runs in the guest):
prints every method call it gets and reports the new state."""
import sys
import warnings
from gi.repository import Gio, GLib

XML = """<node>
<interface name="org.mpris.MediaPlayer2">
  <method name="Raise"/>
  <property name="Identity" type="s" access="read"/>
  <property name="DesktopEntry" type="s" access="read"/>
</interface>
<interface name="org.mpris.MediaPlayer2.Player">
  <method name="Play"/><method name="Pause"/><method name="PlayPause"/>
  <method name="Stop"/><method name="Next"/><method name="Previous"/>
  <method name="SetPosition"><arg type="o"/><arg type="x"/></method>
  <signal name="Seeked"><arg type="x"/></signal>
  <property name="PlaybackStatus" type="s" access="read"/>
  <property name="Metadata" type="a{sv}" access="read"/>
  <property name="Position" type="x" access="read"/>
  <property name="Rate" type="d" access="read"/>
  <property name="CanPlay" type="b" access="read"/>
  <property name="CanPause" type="b" access="read"/>
  <property name="CanGoNext" type="b" access="read"/>
  <property name="CanGoPrevious" type="b" access="read"/>
  <property name="CanSeek" type="b" access="read"/>
</interface></node>"""

state = {"status": "Playing", "position": 1000000, "track": 1}


def metadata():
    n = state["track"]
    return {
        "mpris:trackid": GLib.Variant("o", f"/org/dntest/track/{n}"),
        "mpris:length": GLib.Variant("x", 180000000),
        "xesam:title": GLib.Variant("s", f"Test song {n}"),
        "xesam:artist": GLib.Variant("as", ["Max Mustermann", "demouser"]),
        "xesam:album": GLib.Variant("s", "Test album"),
        "mpris:artUrl": GLib.Variant("s", "file:///tmp/cover.png"),
    }


def get(iface, prop):
    values = {
        "Identity": GLib.Variant("s", "Test Player"),
        "DesktopEntry": GLib.Variant("s", "dntest"),
        "PlaybackStatus": GLib.Variant("s", state["status"]),
        "Metadata": GLib.Variant("a{sv}", metadata()),
        "Position": GLib.Variant("x", state["position"]),
        "Rate": GLib.Variant("d", 1.0),
    }
    return values.get(prop, GLib.Variant("b", True))


def changed(conn, props):
    conn.emit_signal(None, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties",
                     "PropertiesChanged", GLib.Variant("(sa{sv}as)", (
                         "org.mpris.MediaPlayer2.Player",
                         {p: get(None, p) for p in props}, [])))


def call(conn, sender, path, iface, method, params, inv):
    print("call", method, params.unpack() if params else "", flush=True)
    if method == "PlayPause":
        state["status"] = "Paused" if state["status"] == "Playing" else "Playing"
        changed(conn, ["PlaybackStatus"])
    elif method == "SetPosition":
        state["position"] = params.unpack()[1]
        conn.emit_signal(None, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player",
                         "Seeked", GLib.Variant("(x)", (state["position"],)))
    elif method == "Next":
        state["track"] += 1
        changed(conn, ["Metadata"])
        GLib.timeout_add(500, loop.quit)
    inv.return_value(None)


def acquired(conn, name):
    info = Gio.DBusNodeInfo.new_for_xml(XML)
    for i in info.interfaces:
        conn.register_object("/org/mpris/MediaPlayer2", i, call,
                             lambda c, s, p, i, prop: get(i, prop), None)


warnings.simplefilter("ignore", DeprecationWarning)
loop = GLib.MainLoop()
Gio.bus_own_name(Gio.BusType.SESSION, "org.mpris.MediaPlayer2.dntest",
                 Gio.BusNameOwnerFlags.NONE, None, None, None)
# register before the name is visible, so the first property reads succeed
bus = Gio.bus_get_sync(Gio.BusType.SESSION)
acquired(bus, None)
GLib.timeout_add_seconds(int(sys.argv[1]) if len(sys.argv) > 1 else 10, loop.quit)
loop.run()
