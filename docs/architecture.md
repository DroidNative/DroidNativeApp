# Architecture

How DroidNative runs a Linux system on unmodified Android without root.

```
Android app (Kotlin, NDK C)
  EnvService          foreground service that keeps everything alive
    proot + loader    from the APK's native library directory
      Void Linux      glibc rootfs in the app's private storage
        session.sh    D-Bus session bus, PulseAudio, dn-xwm + Xwayland
        applications  one proot process per launched app
    compositor        Wayland server in the app process (C, NDK)
    device bridges    camera, sensors, location, microphone
  MainActivity        Overview, Install and Settings tabs (Compose)
  WindowActivity      one Android task per Linux toplevel window
  LaunchActivity      target of home screen shortcuts
  BootReceiver        optional start with the phone
```

Source layout: `app/` (Android app), `native-host/` (Android builds of
proot, libffi, libwayland-server), `linux-side/` (Void packages built with
xbps-src in Docker), `tests/` (PC and device tests).

## Running programs without root

**Exec restriction.** Apps that target Android 10 (API 29) or newer may not
`execve()` files in their writable data directory. DroidNative therefore
ships every host executable (proot and its loader) as `lib*.so` in the APK
with `android:extractNativeLibs="true"` and runs it from the read-only
native library directory (`native-host/build.sh`, `env/Proot.kt`). Linux
programs in the rootfs are never executed by the kernel directly: proot
turns their `execve` into a start of its loader, which maps the program
with `mmap`. Mapping data files as executable stays allowed.

**Two flavours.** `fdroid` targets API 28, where executing from the data
directory is still allowed (as Termux does), and runs on Android 7.0 (API
24) and newer; it can only be sideloaded or distributed through F-Droid.
`play` targets the current API level and needs Android 10 (API 29). Both
use the native library directory, so there is one code path.

**proot.** The Termux fork of proot emulates root, mounts, namespaces and
hard links (as symlinks, `--link2symlink`) through ptrace. DroidNative
patches it further (`native-host/patches/proot/`): bubblewrap and Flatpak
support, `fchmodat2`, SysV shared memory through memfd (Android denies
`/dev/ashmem` to API 29+ apps), and the V4L2 camera devices. In the
`pivot_root` emulation the mount that becomes the new root moves to `/`
(patch 0005): bubblewrap pivots into a tmpfs mounted over `/tmp` and reads
bind sources such as the X11 socket through `/oldroot/tmp`.

**User.** Applications run as `user` (uid 1000, home `/home/user`);
package management runs as emulated root. `sudo` needs no password.

**Chromium sandbox.** Chromium, Electron and CEF programs abort under
PRoot (SIGTRAP): their sandbox needs a setuid helper or user namespaces.
`libdn-appenv` (droidnative-tools, `files/dn-appenv.c`), listed in the
guest's `/etc/ld.so.preload` by the package's INSTALL script, wraps
`exec*` and `posix_spawn*` and adds `--no-sandbox` when the program is at
least 32 MiB with `icudtl.dat` and a V8 snapshot beside it (that leaves out
helpers such as `opera_crashreporter`, which reject the switch, and
`--type=` child processes). Flatpak apps have their own `/etc`: for a
`flatpak run` of an app that ships such a binary (searched three levels
deep in its files), the hook adds `--filesystem=host-os:ro` and
`--env=LD_PRELOAD=/run/host/usr/lib/droidnative/libdn-appenv.so` to that
run, so the library does the same inside the sandbox. Chromium's own
Flatpak sandbox (the portal's Spawn) cannot work under PRoot either.
Test: `tests/host/appenv/run.sh`.

**libpci.** pciutils ends the whole process (`exit(1)`) when neither
`/sys/bus/pci` nor `/proc/bus/pci` can be read, which Android's SELinux
denies. Chromium probes the GPU through it at start (its GPU process died
three times) and again after a minute, in the browser process: Opera
closed after 60 s. `FakeSysdata` binds an empty directory at
`/proc/bus/pci` (no devices).

**Exit status.** proot exits with the status of its first process, 128 +
signal when that was killed (patch 0004), as a shell would; unpatched it
reported whichever process ended last, so a browser's `exit(1)` came back
as 0.

**Phantom process killer.** Android 12 and newer kill background child
processes beyond 32 across all apps. Keep processes few, or turn the
limit off (developer option on Android 14+, or
`adb shell settings put global settings_enable_monitor_phantom_procs false`).

## Display

The app contains its own Wayland compositor (`app/src/main/cpp/compositor`,
C99): wl_compositor, wl_shm, xdg-shell, linux-dmabuf v3, xdg-decoration
(always server side), text-input v3, xwayland-shell and the private
`dn_xwm_v1`. Every toplevel gets its own Android task (`WindowActivity`)
and is resized to fill it; the Android window is the decoration. Buffers
are copied into GLES textures and drawn on the Android surface.

Content that does not match its window is zoomed to fit, keeping its
aspect ratio, and centred (`dnc_toplevel_view` in `compositor/server.c`;
input is mapped back in `compositor/seat.c`). That covers fixed-size
windows (minimum = maximum size: they get no size or maximized state in
`dnc_toplevel_configure`, `compositor/xdg.c`) and fullscreen games that
draw at their own size. A fullscreen request is answered with the
fullscreen state; with maximized, SDL takes it as refused and sizes itself
to the output. The output (`compositor/output.c`) is reported in landscape,
since SDL2 reads its mode once at start and sizes exclusive fullscreen by
it, and a client's output later follows its window's size for clients that
track changes.

A toplevel whose minimum size exceeds its window (Opera: 660 logical px
wide, a phone window at scale 2 is 540) is offered the window's aspect
ratio scaled up to that minimum, so the zoom fills the window without
bars (`dnc_toplevel_configure`; `set_min_size` reconfigures).

X11 programs run on rootless Xwayland (`DISPLAY=:0`). `dn-xwm`
(`linux-side/srcpkgs/droidnative-tools/files/dn-xwm.c`) starts it and is
its window manager: it tells the compositor which X window is a toplevel
or a popup (menus) through `dn_xwm_v1`. X toplevels take their Android
window's size, except fixed-size ones (WM_NORMAL_HINTS), which keep theirs,
and fullscreen ones (`_NET_WM_STATE_FULLSCREEN`), which get the X screen's
size because SDL draws for that size without asking; the compositor zooms
both. Xwayland listens only on the socket
file in the guest's `/tmp`, not on an abstract socket (those are shared
with every Android app).

A program that ends abnormally after its window opened (error code,
killed, crashed) is reported with a notification naming the cause, e.g.
SIGKILL as "usually low memory"; it opens the loading screen's log view
(`LaunchActivity.reportEnded`). Ends the user caused (swiped away, stopped
environment, SIGTERM) are not reported. When Android ended DroidNative
itself (low memory killer, crash), the next start says so once
(`MainActivity.lastExitNotice`, ApplicationExitInfo, API 30+).

Closing: swiping a window away in Android's recent apps asks the
application to close it (`xdg_toplevel.close`, `WM_DELETE_WINDOW`). If the
process still has windows 3 seconds later and none of them is on screen
(apps often answer a close with a dialog or a restored session, which
Android does not let them show from the background), it is ended: the
Wayland client is disconnected, the X client killed through dn-xwm
(`Compositor.closeFromHost`). Back moves a window to the background
without closing it. A window opened while DroidNative is in the background
appears as a notification; tapping it shows the window.

Hardware keyboards send their scancodes unchanged. Android has no public
API for the name of the chosen keyboard layout, so `display/HardwareKeyboard.kt`
generates an xkb layout from the keyboard's character map (what each
physical key types plain, with Shift, AltGr and both, dead keys included;
`InputDevice.getKeyCodeForKeyLocation`, Android 13+), compiles it in the
guest with `xkbcli` and gives it to the compositor, which sends the new
keymap to every client. Right Alt then selects level 3 (AltGr). Without a
hardware keyboard the default US keymap stays. Mouse wheels send discrete
notches (X11 programs need them), side buttons arrive as BTN_SIDE and
BTN_EXTRA.

Pointer capture: the compositor implements pointer-constraints and
relative-pointer (`compositor/constraints.c`), which SDL games and
Xwayland (X11 pointer grabs) use. A lock is active while its window has
focus; absolute motion is then ignored and only relative motion is sent.
Android hears about it (`onPointerLock`): the emulated touchpad hides its
cursor and sends finger movement as relative motion, emulated mouse
clicks turn drags into relative motion, and a real mouse is captured with
Android's pointer capture so it delivers raw movement.

On-screen buttons: [on-screen-buttons.md](on-screen-buttons.md).

Input: touch is turned into touch events, mouse clicks or a touchpad with
a drawn cursor (`display/TouchInput.kt`); keys and the on-screen keyboard
go through wl_keyboard and text-input. Windows map back to the
application that opened them through the process tree, for per-app
settings ([per-app-settings.md](per-app-settings.md)).

## Graphics

- **Adreno (Qualcomm):** Mesa's Turnip driver with the KGSL backend talks
  to `/dev/kgsl-3d0` directly from the glibc rootfs: hardware Vulkan.
  OpenGL runs on top of it through Zink. Low-end Adreno (610, 702) need
  `TU_DEBUG=noconform` to expose Vulkan 1.3, which the environment sets.
- **Other GPUs:** software rendering (llvmpipe/lavapipe) for now.
- **Flatpak apps** use their runtime's Mesa, so DroidNative installs its
  own GL extension `org.freedesktop.Platform.GL.droidnative` (Turnip, Zink)
  and selects it with `FLATPAK_GL_DRIVERS`.
- `dn-gl <auto|zink|gl4es|llvmpipe>` picks the OpenGL backend per program.

Android kernels lack implicit dma-buf synchronisation, so a Mesa patch
makes the Wayland WSI wait for each frame on the CPU before presenting.

Text input v1 (`compositor/text_input.c`) exists beside v3 for Chromium
and Electron (Chromium 126 knows only v1, later ones v3 only on request): a v1 input counts as
active between activate and deactivate on the focused surface.

## AppImages

No FUSE without root, so AppImages are unpacked once.
`dn-appimage` (droidnative-tools) does the work in the guest: it finds the
squashfs payload behind the runtime's ELF section headers and unpacks it
with unsquashfs (the runtime itself is only the fallback, older ones need
`libz.so`), into `~/.local/share/droidnative/appimages/<id>`, and writes
`dn-appimage-<id>.desktop`. Own titles and icons live in `.custom/` beside
the AppDirs and survive a reinstall. `dn-appimage run` starts Electron
apps (those with `chrome-sandbox`) on native Wayland with
`--enable-wayland-ime`, so text fields open the keyboard.

The app side is "Manage AppImages" on the Install tab
(`ui/AppImagesActivity.kt`, `apps/AppImages.kt`): the system file picker
supplies the file, which is checked (ELF, architecture, free space),
copied into the guest's `/tmp` and installed with `dn-appimage install
--move` on a background thread; the list reads the rootfs directly, and
title, icon (scaled into a 256 px square PNG) and removal go through
`dn-appimage rename|icon|remove`.

## Sound, session and storage

Services: `env/Services.kt` runs runit's `runsvdir` on `/var/service` as
emulated root while the environment runs. Void's default service directory
(gettys, udevd) does not fit, so setup.sh points `/var/service` at the
empty `/etc/runit/runsvdir/droidnative`; services are enabled the usual way
(`ln -s /etc/sv/NAME /var/service/`). With "Start with the phone" the
`BootReceiver` starts the environment, and with it the services, after
boot.

Light and dark: `apps/GuestTheme.kt` passes DroidNative's colour scheme to
programs it starts: `gtk-application-prefer-dark-theme` in GTK's
settings.ini, `ADW_DEBUG_COLOR_SCHEME` for libadwaita (no settings portal
runs here).

PulseAudio runs in the guest with a pipe sink; the app reads the PCM from
the FIFO and plays it through `AudioTrack` (`env/AudioOutput.kt`, at
Android's audio thread priority so busy guests cannot starve it). The sink
keeps the FIFO full, so the pipe's 64 KiB (340 ms) were pure latency;
`AudioOutput` shrinks it to 8 KiB with `F_SETPIPE_SZ` (dropping what is
queued once if it is fuller) and the AudioTrack buffer absorbs late
deliveries instead. Programs asking for small buffers (OpenAL Soft, SDL)
underrun many times a second behind the pipe sink, so every guest program
gets `PULSE_LATENCY_MSEC` (`env/Proot.kt`, Flatpak apps inherit it). Both
come from the "Audio buffer" setting (`Prefs.audioBufferMs`, default 150
ms, measured gap-free with SuperTux 2 on GPU and software rendering); the
AudioTrack part applies at once, the program part on its next start. The
microphone works the other way round (`devices/MicBridge.kt`). A D-Bus
session bus runs alongside (`assets/guest/session.sh`).

With "All files access" granted, shared storage is bound at `/media/sdcard`
and other volumes at `/media/<volume id>` (`env/StorageMounts.kt`).

Cameras, sensors, location and microphone: see
[device-passthrough.md](device-passthrough.md).

## Packages

The rootfs is the official Void Linux glibc tarball. `assets/guest/setup.sh`
installs the default packages from Void's mirrors plus DroidNative's own
packages from a local repository inside the APK (`app/src/main/assets/repo`,
built by `linux-side/build-packages.sh`): the Turnip and Zink Mesa builds,
the Flatpak GL extension, gl4es and `droidnative-tools` (dn-gl, dn-xwm,
dn-appimage, libdn-appenv). The setup runs again when an app update ships a
different setup script or repository.

**Flatpak store** ("Manage Flatpaks", `ui/FlatpakActivity.kt`,
`apps/Flatpak.kt`): the user installation (`flatpak --user`). The catalogue
is read straight from each remote's appstream data in the rootfs
(`~/.local/share/flatpak/appstream/<remote>/<arch>/active`, names,
summaries, descriptions, cached icons), so search needs no guest process;
"Refresh catalogue" runs `flatpak update --appstream`. Installs, removals,
updates, unused runtimes and remotes (`remote-add`, with `--from` for
`.flatpakrepo` addresses; `remote-delete`) run one at a time in the
background through the guest's flatpak, shown as a job card. Removing an
app's data deletes `~/.var/app/<id>` directly: flatpak's `--delete-data`
also needs the session bus, which only runs with the environment. On
Adreno phones "Remove unused runtimes" also removes
`org.freedesktop.Platform.GL.default`, since `FLATPAK_GL_DRIVERS` selects
DroidNative's own extension.

**Presets** (`apps/Presets.kt`): the DroidNativePresets repository
(github.com/DroidNative/DroidNativePresets) is kept unpacked in
`files/presets`, refreshed through its tarball's ETag when software is
installed (at most every 10 minutes). Per package (`<source>/<package>/`)
`settings.ini` sets per-app settings, by desktop id, for apps the user has
not configured, `layout.xml` files become button layouts, `helpers/` go to
`/etc/DroidNative/helpers/<source>/<package>/`, and `preinstall.sh` /
`postinstall.sh` run as the guest user with the preset folder at
`/run/droidnative-preset` (`DN_PRESET_DIR`, `DN_HELPERS`, `DN_PACKAGE`;
output in `files/logs/preset-<source>-<package>.log`). Where it applies:
Flatpak installs from the store (package = app id, pre- and postinstall),
AppImage installs (package = the AppImage's own desktop file name; every
section maps to its `dn-appimage-<id>` entry; postinstall only) and xbps
packages: installs from the native package screen run the requested
packages' preinstall.sh; after every xbps job and at environment start
(packages installed from a terminal) `xbps-query -l` is compared with the
last list, and new packages get their preset (the first check covers
everything installed). Presets applied at environment start are
announced by notification. The source is a setting (Settings, Presets;
`Prefs.presetSource`, default the DroidNativePresets repository): GitHub
addresses become their codeload tarball (`Presets.tarballUrl`, branch
main or /tree/<branch>), anything else is taken as a .tar.gz; a new
source replaces the cache. "Apply to installed software" runs every
installed package's preset again (settings still only for unconfigured
apps).

**Native packages** ("Manage Native (xbps) Packages",
`ui/XbpsActivity.kt`, `apps/Xbps.kt`; replaced OctoXBPS): the catalogue is
`xbps-query -Rs ""` (about 14000 packages, under a second), searched in
the app by name or description; file search is `xbps-query -o '*text*'`
over installed packages, as in OctoXBPS. Details come from `xbps-query
[-R] <pkg>`, files from `-f`; an installed package's desktop entries give
it an Open button. Installs, removals (optionally with dependencies
nothing else needs, `-R`), orphan removal, cache cleaning, source sync
(`xbps-install -S`) and the full update (`xbps-install -Syu`; when xbps
demands its own update first, `xbps-install -Syu xbps` and a retry) run
as the emulated root, one at a time. The job card shows a progress bar
from the output (package count from "N packages will be installed",
then the download, unpack and configure lines per package); "Show
output" opens the complete output as a terminal view. Pending updates
come from a dry run (`xbps-install -un`). Packages DroidNative needs
(`Xbps.isProtected`) cannot be removed there.
