# Testing DroidNative (backend phase)

There are three layers of tests. The first two run on a Linux PC, the third on
a phone over adb.

| Layer | Command | What it covers |
|---|---|---|
| JVM unit tests | `./gradlew testPlayDebugUnitTest` | tar extraction (GNU/pax, links), checksum list parsing, preset settings.ini |
| Host compositor | `tests/host/compositor/run.sh` | Wayland protocol handling of the compositor core (null renderer, ASan/UBSan) with `dn-wltest` and `weston-simple-shm`; `dnc-decotest` checks the KDE decoration mode and the first-frame size report against a normal and a desktop mode compositor (`DNC_HEADLESS_DESKTOP=1`) |
| Host camera emulation | `tests/host/v4l2/run.sh` | PRoot's V4L2 device emulation against a fake camera, `v4l2-ctl` as client (needs the PC proot build in `.cache/hostproot`) |
| Host per-app environment | `tests/host/appenv/run.sh` | `libdn-appenv` and dn-gl: which child programs keep an application's settings, `--no-sandbox` for Chromium based programs (PC guest of `.cache/hostproot`) |
| Host notifications | `tests/host/notify/run.sh` | dn-notifyd: notifications (actions, hints, expiry, dismiss) and an MPRIS player relayed over the bridge socket; private bus in the PC guest (needs glib-devel, json-glib-devel, python3-gobject there) |
| Host guest chroot | `tests/host/guest/chroot.sh <rootfs> <rundir> <cmd>` | runs guest scripts (`setup.sh`) in a Void rootfs on the PC |
| proot storage extension | `tests/droidnative/run.sh <build dir>` in the proot fork | exec from noexec mounts, FAT-like volumes, FUSE emulation, disk images (PC, guest is the PC itself) |
| proot fork, other | `binfmt.sh`, `tracer.sh`, `net.sh`, `seccomp.sh` in the fork's `tests/droidnative/` (same argument) | binfmt_misc emulation, hidden TracerPid, generated `/proc/net/tcp`, SIGSYS of the program's own seccomp filter |
| Device suite | `tests/device/run.sh` | everything on real hardware, see below |

## Building

1. `native-host/build.sh`: proot (the pinned commit of
   https://github.com/DroidNative/proot, with SQLite), libffi and
   libwayland-server for all four ABIs with the NDK (r29, path from
   `local.properties`). Needs `curl`, `git`, `make`, `rsync`, `python3`, a
   host C compiler and libexpat headers. `DN_PROOT_SRC=<checkout>` builds a
   local proot checkout instead.
2. `./gradlew assemblePlayDebug` or `assembleFdroidDebug`. The guest's
   DroidNative packages come from the published xbps-repository.
   To try packages built locally there (`./xbps.sh build`, signed with
   your own key from `./xbps.sh keygen`), serve its `out/repo` and build
   with `DN_XBPS_REPO=http://127.0.0.1:8000 DN_XBPS_KEY=<key>.plist`
   after `adb reverse tcp:8000 tcp:8000`. Changes to `guest-tools/` reach
   the published packages with the next workflow run after they land on
   `main`; `DN_APP_SRC=<this checkout>` builds them locally.

## Device suite

```
tests/device/run.sh [-f play|fdroid] [-s serial] [-t TestClass] \
                    [--no-build] [--push-rootfs] [--fix-phantom]
```

- The app is installed with `adb install -r` and never uninstalled, so the
  rootfs and the installed packages survive between runs. The first run
  downloads the Void rootfs (about 60 MB) and the packages (a few hundred
  MB); later runs skip that.
- `--push-rootfs` downloads the rootfs once on the PC (`.cache/rootfs/`) and
  pushes it to the app's external files directory.
- `--fix-phantom` disables Android 12+'s phantom process killer through adb.
  Without it Android may kill guest processes when more than 32 run.
- `-t T06VulkanWaylandTest` runs a single class.

Results land in `tests/results/<date>-<model>-<flavour>/`:

| File | Content |
|---|---|
| `instrument.txt` | raw JUnit output from `am instrument` |
| `test-reports/report.txt` | one line per measurement (fps, frame counts, device nodes, renderers) |
| `test-reports/*.log` | full output of every guest command |
| `logs/setup.log` | package installation log |
| `logcat.txt` | app, compositor and test log lines |

Please send back the whole results directory after a run.

### What the tests check

| Class | Checks |
|---|---|
| T01RootfsTest | device facts (GPU nodes, ABI, kernel), host executables, rootfs install |
| T02ProotTest | proot exec from `nativeLibraryDir`, fake root, fork/exec speed, hard link emulation, shared storage |
| T03GuestSetupTest | package installation, keymap, bubblewrap inside proot (needed by Flatpak) |
| T04GraphicsTest | KGSL/dma-heap access from the guest, `vulkaninfo`, Zink (surfaceless), virgl (surfaceless) and gl4es on it where there is no Turnip |
| T05CompositorTest | compositor with `dn-wltest`: wl_shm and dmabuf paths, a real window plus screenshot, hidden-window throttling |
| T06VulkanWaylandTest | `vkcube` on Wayland: dmabuf (Turnip) path, `MESA_VK_WSI_DEBUG=sw` fallback, run without the present wait patch |
| T07ZinkWaylandTest | GL through the `dn-gl` backends: Zink (GLES and desktop GL), gl4es, llvmpipe |
| T08AppImageTest | `dn-appimage`: install, desktop entry, run, own title, remove (downloads appimagetool) |
| T09FlatpakTest | Flathub remote, install of a small app plus runtime (several hundred MB on first run), run in the bubblewrap sandbox, GLES app from the sandbox in a real window (accelerated through the DroidNative GL extension on KGSL devices) |
| T10AudioTest | PulseAudio in the guest, a 440 Hz tone reaching Android's AudioTrack (audible, 2 s each from the guest and from a Flatpak sandbox), D-Bus session bus |
| T11DevicesTest | device passthrough (docs/device-passthrough.md): camera via V4L2, sensors via IIO sysfs, location via `/dev/gps0`, microphone via PulseAudio; brings the app to the foreground (Android blocks camera and microphone in the background) |

### Expectations per test device

| Device | Arch | Expected |
|---|---|---|
| Fairphone 4 (Adreno 619) | aarch64 | everything passes; Vulkan is Turnip |
| Redmi Note 8T (Adreno 610) | aarch64 | everything passes (Turnip needs TU_DEBUG=noconform, set automatically) |
| Galaxy S5 (Adreno 330) | armv7l | no Turnip (a3xx): Zink tests are skipped, Vulkan falls back to lavapipe, OpenGL is virgl on Android's driver |

Turnip tests decide by the GPU model Android reports (`TestEnv.hasTurnipGpu`,
Adreno 600 and up), not by `/dev/kgsl-3d0`. A 32-bit install on a 64-bit
phone (`adb install --abi armeabi-v7a`) runs the armv7l environment there;
the aarch64 one is parked in `files/parked-aarch64` meanwhile
(`EnvPaths.matchArch`) and comes back with a 64-bit install.

A failing test is still useful: the logs say how far the stack got.

## Debugging on the phone

`tests/device/guest-sh.sh '<command>'` runs a command in the guest through
`adb run-as` (debug builds), with the same proot arguments as the app. Wayland
clients need the compositor, which runs inside the app: open the app once
(`adb shell am start -n app.droidnative/.MainActivity`) and press Start on
the Overview tab so the environment service runs. Windows do not open from there (background start), but
clients run and get frame callbacks. It runs as the emulated root;
`DN_USER=1` runs as the guest user instead, as the app starts programs
(needed for Chromium and Electron, which refuse root). Examples:

```
tests/device/guest-sh.sh 'vulkaninfo --summary'
tests/device/guest-sh.sh 'dn-gl zink eglinfo -B -p wayland'
tests/device/guest-sh.sh 'gdb -q -batch -ex run -ex bt --args vkcube --wsi wayland --c 50'
DN_USER=1 tests/device/guest-sh.sh 'dn-appimage list'
```

`tests/device/app-sh.sh [-d] '<command>'` does the same from inside the real app
process (through the instrumentation). It is slower but faithful: the app has
a seccomp filter and its own mount namespace, `run-as` has neither. Use it
when something only fails in the tests. `-d` starts the device passthrough
bridges around the command (open the app first: camera and microphone need
it in the foreground).

The device suite restarts the app process (`am instrument` force-stops it),
so an open DroidNative window closes when a run starts.

Shared storage (`/media/sdcard`, `/media/<volume id>`) needs "All files
access" in the Settings tab; the suite switches it on for itself. It is not
visible under `run-as`; to get files into the guest, stream them into the
rootfs: `adb exec-in run-as app.droidnative sh -c 'cat > files/rootfs/tmp/x.xbps' < x.xbps`
(`/tmp/x.xbps` in the guest; a directory of packages plus repodata
installs with `xbps-install -R /tmp/<dir> <pkg>`, local repositories need no
signature).

x86 emulation: `tests/device/x86/build.sh` builds `hello-x86_64` and
`hello-i386` on the PC; copied into the guest's `/tmp` they must print
their greeting when box64 (or box86) is installed.

`dn-wltest --lock` (package `droidnative-tools`) locks the pointer and
prints at exit whether the lock was granted and how much relative and
absolute motion arrived; use it to check pointer capture in a window.

## OpenGL backends

`dn-gl <auto|zink|virgl|gl4es|llvmpipe> <command>` (package
`droidnative-tools`) picks the GL implementation for one program; the per-app
"Graphics backend" setting uses it. `auto` is Zink when Turnip drives the GPU,
virgl when the app's virgl server runs, llvmpipe otherwise
(docs/architecture.md, "Graphics").
