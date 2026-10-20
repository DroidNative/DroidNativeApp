# Device passthrough

Phone hardware reaches Linux programs through the interfaces Linux uses for
real hardware, emulated without kernel support. Each device is switched on
in the app (Settings, "Device access"); Android asks for its permission
then. Binds are set up when a PRoot process starts, so a change takes effect
after restarting the environment.

| Device | Guest interface | Host side |
|---|---|---|
| Cameras | `/dev/video0`, `/dev/video1`, ... (V4L2 capture) | `devices/CameraBridge.kt`, PRoot extension `dn_v4l2` |
| Sensors | `/sys/bus/iio/devices/iio:deviceN` (IIO sysfs) | `devices/SensorBridge.kt` |
| Location | `/dev/gps0` (NMEA 0183 stream) | `devices/LocationBridge.kt` |
| Microphone | PulseAudio source `android_mic` | `devices/MicBridge.kt`, `assets/guest/session.sh` |

`devices/Devices.kt` decides which binds a PRoot process gets
(`Devices.binds`, called from `Proot.binds`) and starts the bridges with the
environment (`EnvService.onCreate`). Host files live in `files/dev/` of the
app. Bridges feed devices only while a guest program uses them, so an
enabled but unused device costs nothing (sensors excepted, see below).

## Cameras: V4L2

Every Android camera with YUV output becomes `/dev/videoN`, back cameras
first (`CameraBridge.discover`). The node reports as character device 81:N.

Discovery, so that udev based programs (GStreamer's device monitor, Cheese)
find the cameras (`Devices.binds`):

- `/sys/class/video4linux/videoN` (also at `/sys/dev/char/81:N`) holds
  `uevent`, `dev`, `name` (Android's camera label) and a `subsystem` link.
- `/sys/subsystem/video4linux/devices/videoN`: relative links to the class
  entries. Android forbids listing `/sys/class` and `/sys/bus`, which udev
  normally scans; eudev prefers the `/sys/subsystem` layout when it exists,
  and Android has none to hide.
- `/run/udev/data/c81:N`: the udev database entry udevd's v4l rules would
  write (`ID_V4L_CAPABILITIES=:capture:`, `ID_V4L_PRODUCT`, ...). Without
  it a device counts as not initialised and GStreamer skips it.

Android also forbids listing `/dev`, so programs that find cameras by
globbing `/dev/video*` see none; opening `/dev/videoN` by name works.

Supported:

- `VIDIOC_QUERYCAP`, `ENUM_FMT`, `ENUM_FRAMESIZES`, `ENUM_FRAMEINTERVALS`
  (30 fps), `G/S/TRY_FMT`, `G/S_PARM`, `ENUMINPUT`, `G/S_INPUT`,
  `G/S_PRIORITY`.
- Formats `YUYV` and `NV12`, sizes as the camera offers them up to
  1920x1080 (at most 16). Colour space JPEG (full range BT.601).
- Streaming with `V4L2_MEMORY_MMAP` and `V4L2_MEMORY_USERPTR`: `REQBUFS`
  (up to 8 buffers), `QUERYBUF`, `QBUF`, `DQBUF`, `STREAMON/OFF`. `DQBUF`
  blocks, honours `O_NONBLOCK` (EAGAIN), and `poll`/`select`/`epoll` report
  readiness when a frame is waiting.

Not supported: `read()` I/O, `DMABUF` memory, `CREATE_BUFS`, controls
(`QUERYCTRL` etc. answer ENOTTY), crop/selection, events, 32-bit programs
on a 64-bit PRoot. Frames are in sensor orientation, which is landscape on
phones; Android's `SENSOR_ORIENTATION` is not applied.

How it works: `/dev/videoN` is a FIFO (`files/dev/videoN`). PRoot's
extension (`src/extension/dn_v4l2/` in the proot fork) recognises descriptors of these FIFOs and answers
the ioctls itself. `mmap` of a buffer becomes anonymous memory. On first use
it connects to `files/dev/videoN.sock`, where the app sends a hello record
and the frame memory (`videoN.shm`) as a descriptor. `STREAMON`/`STREAMOFF`
and format changes go to the app as commands; the app opens the camera,
converts each `YUV_420_888` image into a slot of the shared memory and
writes a 24-byte token to the FIFO. `DQBUF` is turned into a `read` of one
token (that gives the blocking and poll semantics), then the frame is
copied into the dequeued buffer. The guest's dequeue count in the shared
memory keeps the app at most two frames ahead. Record layouts:
`dn_v4l2_proto.h`. PRoot finds the cameras at start through
`PROOT_DN_VIDEO_DIR`.

## Sensors: IIO sysfs

`/sys/bus/iio/devices` holds one directory per sensor the phone has, with
`name`, `label` (Android's sensor name) and the usual attributes:

| `name` | Files | Unit (value = raw x scale) |
|---|---|---|
| `accel_3d` | `in_accel_{x,y,z}_raw`, `in_accel_scale`, `in_accel_mount_matrix` | m/s^2 |
| `gyro_3d` | `in_anglvel_{x,y,z}_raw`, `in_anglvel_scale`, `in_anglvel_mount_matrix` | rad/s |
| `magn_3d` | `in_magn_{x,y,z}_raw`, `in_magn_scale`, `in_magn_mount_matrix` | gauss |
| `als` | `in_illuminance_input` | lux |
| `pressure` | `in_pressure_input` | kPa |
| `proximity` | `in_proximity_raw`, `in_proximity_scale` | cm (Android value) |

Axes follow Android's device coordinate system (x right, y up, z out of the
screen in natural orientation); mount matrices are identity. Values are
updated in place at most every 50 ms, right-aligned in a fixed width, so a
program may keep the file open and re-read from offset 0. No buffered
(`/dev/iio:deviceN`) interface and no udev events: `iio-sensor-proxy` does
not find these sensors; programs reading sysfs directly do. The sensors are
read for as long as the environment runs with the switch on.

## Location: NMEA

`/dev/gps0` delivers `$GPGGA` and `$GPRMC` sentences (CRLF terminated,
checksummed) once per fix, like a serial GPS receiver. Android location
updates run only while a program has the device open (the app checks every
2 s); the last known fix is sent at once. GPS provider when fine location
is granted, otherwise fused or network. Read with `cat /dev/gps0`, or point
gpsd or another NMEA consumer at it. No tty ioctls (baud rate etc.).

## Microphone: PulseAudio source

With the switch on, `session.sh` (started with `DN_MIC=1`) loads
`module-pipe-source` as `android_mic` (s16le, 48 kHz, mono) on
`/run/droidnative/mic.fifo` and makes it the default source. A
`pactl subscribe` loop writes `1` to `/run/droidnative/mic.active` while any
stream records from it, `0` otherwise; the app opens Android's microphone
(`AudioRecord`) only while it reads `1`. PulseAudio, ALSA (through the
pulse plugin) and Flatpak apps with `--socket=pulseaudio` all see it. There
is no `/dev/snd` capture device.

## Android restrictions

Android only gives camera, microphone and location to an app in the
foreground, or to a foreground service of the matching type (`camera`,
`microphone`, `location`). The environment service is `specialUse`, so
these devices work while a DroidNative window (or the main screen) is
visible. In the background the camera fails to open, the microphone
records silence and location updates stop.

## Tests

- `tests/host/v4l2/run.sh`: the PRoot V4L2 emulation on the PC against a
  fake camera (`feed.c`), with `v4l2-ctl` as client.
- Device suite `T11DevicesTest`: camera info and 30 streamed frames, sensor
  files, a fix from `/dev/gps0` (skipped without one), 3 s from
  `android_mic`. `tests/device/app-sh.sh -d '<cmd>'` runs a guest command
  with the bridges running.
