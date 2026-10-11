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

PulseAudio runs in the guest with a pipe sink; the app reads the PCM from
the FIFO and plays it through `AudioTrack` (`env/AudioOutput.kt`). The
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
