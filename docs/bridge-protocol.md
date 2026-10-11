# Bridge protocol

The guest talks to the app over one Unix stream socket, served by the app
(`bridge/Bridge.kt`, started by `EnvService`) at `files/run/bridge.sock`,
which the guest sees as `/run/droidnative/bridge.sock`. The only client is
`dn-notifyd` (package `droidnative-tools`, `files/dn-notifyd.c`), started by
`session.sh` once the session bus is up. It reconnects every second while
the socket is missing, and a new connection replaces the old one.

Every message is one JSON object on one line with a `type` field. Unknown
types and fields are ignored, so either side can add to them.

## Guest to app

| type | fields | meaning |
|---|---|---|
| `hello` | `version` (1) | sent after connecting |
| `notify` | see below | a notification was posted or replaced |
| `close` | `id` | the notification is gone (closed by its sender or expired) |
| `player` | see below | a media player's full current state |
| `player-gone` | `name` | the player left the bus |

`notify` carries what `org.freedesktop.Notifications.Notify` got:

- `id`: the notification id; the same id again replaces it.
- `pid`: the sender's process.
- `app`: its application name.
- `icon`: icon name or path.
- `title`: the summary.
- `body`: the body, with its markup.
- `actions`: `[[key, label], ...]`.
- `urgency`: 0 to 2.
- `desktop`, `category`, `imagePath`, `transient`, `resident`: from the hints.
- `image`: present when the sender passed `image-data`. dn-notifyd writes the
  raw pixels to `/run/droidnative/notify/<id>.rgba` and the object gives
  `file`, `width`, `height`, `rowstride` and `channels` (3 or 4, 8 bits each).

An expire timeout of -1 (the default) means the notification stays until
it is dismissed, like other Android notifications. dn-notifyd handles
positive timeouts itself and sends `close`.

`player` describes one `org.mpris.MediaPlayer2.*` bus name:

- `name`, `pid`, `identity`, `desktop`.
- `status`: `Playing`, `Paused` or `Stopped`.
- `trackid`, `title`, `artist` (several are joined with ", "), `album`, `art`
  (the `mpris:artUrl`, often `file://`).
- `length` and `position` in microseconds; -1 when unknown.
- `rate`.
- `canPlay`, `canPause`, `canNext`, `canPrevious`, `canSeek`.

Changes are collected for 50 ms before they are sent. Position is sent with
every state change and after a seek, and the app extrapolates it while the
player plays.

## App to guest

| type | fields | meaning |
|---|---|---|
| `action` | `id`, `key` | emits `ActionInvoked`; `default` when the notification was tapped |
| `closed` | `id` | dismissed by the user; emits `NotificationClosed` with reason 2 |
| `media` | `name`, `cmd` | `play`, `pause`, `playpause`, `stop`, `next`, `previous`, `raise` |
| `seek` | `name`, `trackid`, `position` | MPRIS `SetPosition`, in microseconds |

## Android side

- **Notifications** (`bridge/LinuxNotifications.kt`): one Android
  notification each, on three channels by urgency. The large icon comes from
  the image, the image path, the icon or the application's icon, in that
  order. The application is the `desktop-entry` hint or the desktop entry
  the sending process was started from (`WindowSettings.resolveApp`).
- **Taps** (`bridge/NotificationOpenActivity.kt`): sends `default` if the
  notification has that action, then opens the application's window. If
  there is none, it waits 3 s for a new window, or starts the application.
- **Buttons and dismissing:** `bridge/NotificationReceiver.kt`.
- **Media players** (`bridge/MediaPlayers.kt`): one `MediaSession` plus a
  media-style notification per player.

Test on the PC: `tests/host/notify/run.sh`.
