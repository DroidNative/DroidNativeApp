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
`/dev/ashmem` to API 29+ apps), and the V4L2 camera devices.

**User.** Applications run as `user` (uid 1000, home `/home/user`);
package management runs as emulated root. `sudo` needs no password.

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

X11 programs run on rootless Xwayland (`DISPLAY=:0`). `dn-xwm`
(`linux-side/srcpkgs/droidnative-tools/files/dn-xwm.c`) starts it and is
its window manager: it tells the compositor which X window is a toplevel
or a popup (menus) through `dn_xwm_v1`. Xwayland listens only on the socket
file in the guest's `/tmp`, not on an abstract socket (those are shared
with every Android app).

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
Android's audio thread priority so busy guests cannot starve it). The pipe
sink reports its latency poorly, and programs asking for small buffers
(OpenAL Soft, SDL) then underrun many times a second; every guest program
therefore gets `PULSE_LATENCY_MSEC=150`, measured gap-free with SuperTux 2
on GPU and software rendering. The
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
