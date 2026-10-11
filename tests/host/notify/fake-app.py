#!/usr/bin/env python3
"""App side of the bridge for tests/host/notify/run.sh: serves bridge.sock,
writes every message from dn-notifyd to a log and answers like the app
would when a user taps (action, dismiss, media buttons, seek)."""
import json
import os
import socket
import sys

path, log = sys.argv[1], sys.argv[2]
if os.path.exists(path):
    os.unlink(path)
srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
srv.bind(path)
srv.listen(1)
srv.settimeout(30)
conn, _ = srv.accept()
out = open(log, "w", buffering=1)


def send(**msg):
    conn.sendall((json.dumps(msg) + "\n").encode())


seeked = set()
for raw in conn.makefile("r", encoding="utf-8"):
    out.write(raw)
    m = json.loads(raw)
    t = m.get("type")
    if t == "notify" and m["title"] == "Action test":
        send(type="action", id=m["id"], key=m["actions"][0][0])
    elif t == "notify" and m["title"] == "Dismiss test":
        send(type="closed", id=m["id"])
    elif t == "player" and m["status"] == "Playing" and m["name"] not in seeked:
        seeked.add(m["name"])
        send(type="media", name=m["name"], cmd="playpause")
        send(type="seek", name=m["name"], trackid=m["trackid"], position=42000000)
        send(type="media", name=m["name"], cmd="next")
