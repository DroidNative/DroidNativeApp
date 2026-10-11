# Per-app settings

Long press on an application in the Overview, "Change per-app settings"
(`ui/AppSettingsDialog.kt`). Stored per desktop entry id in the shared
preferences `app_settings` as JSON (`apps/AppSettings.kt`); unset fields
use the global settings (Settings tab, `env/Prefs.kt`).

| Setting | Applied by | When |
|---|---|---|
| Graphics backend (auto, Zink, gl4es, llvmpipe) | `dn-gl <backend>` in front of the command (`LinuxApps.launch`) | next start |
| Resolution (`720` or `50%`) | `WindowActivity`: surface rendered at that size (`setFixedSize`), Android scales it | windows opened afterwards |
| UI density (DPI) | `LinuxApps.dpiEnv`: `GDK_DPI_SCALE` and `QT_SCALE_FACTOR` for the part the integer wl_output scale does not cover | next start |
| Touch input (touch, emulated mouse clicks, emulated touchpad) | `display/TouchInput.kt` | windows opened afterwards |
| Rotation (follow phone, portrait, landscape) | `WindowActivity.requestedOrientation` | windows opened afterwards |
| On-screen buttons | not implemented yet (shown disabled) | |

## Global DPI

The wl_output scale is the whole part of DPI / 96 (`EnvService.scaleFor`),
for all windows; the rest goes into the toolkit variables above. Default:
96 per whole Android density step (`Prefs.defaultDpi`, 2.75 gives 192). It
does not depend on the resolution: a lower resolution with the same DPI
makes the UI larger on screen, a lower DPI makes it smaller again.

## Which window belongs to which application

The compositor reports each toplevel's client pid (`wl_client_get_credentials`;
PRoot does not virtualise pids). `LinuxApps.launch` starts
`sh -lc "echo <id> > /run/droidnative/apps/$$; export DN_APP_PID=$$; exec <cmd>"`,
so the launched program keeps a registered pid. `WindowSettings.resolveApp`
walks the parent chain (`/proc/<pid>/stat`) from the client to a registered
pid. Programs not started by DroidNative use the entry whose program
(`DesktopEntry.program`, the Exec basename) matches their command line.

## Programs started by the application

| Choice | Window settings of a child's windows | Environment of child programs |
|---|---|---|
| Use these settings too (default) | the application's | inherited as usual |
| Unless in the application list | a listed program's own, else the application's | listed programs: their own; others: inherited |
| Use their own or the global settings | a listed program's own, else global | listed programs: their own; others: the defaults |

The environment part needs `libdn-appenv.so` (package droidnative-tools,
`linux-side/srcpkgs/droidnative-tools/files/dn-appenv.c`), preloaded only
for the two restricted choices. It wraps `exec*` and `posix_spawn*`: a new
program in another process than `DN_APP_PID` loses the variables listed in
`DN_APP_VARS` and recorded by dn-gl (`DN_GL_VARS`, previous values in
`DN_GL_ORIG_<name>`), and the preload itself. A listed program instead gets
its own settings from `/run/droidnative/apps.list` (written by
`LinuxApps.writeAppList`: program, backend, GDK_DPI_SCALE, QT_SCALE_FACTOR),
run through dn-gl. Spawns inside glibc (`system`, `popen`) are not seen
directly; the shell they start carries the preload and applies the rule.
Flatpak apps are not covered (their sandbox gets the Flatpak GL extension).

Test: `tests/host/appenv/run.sh` (PC guest).
