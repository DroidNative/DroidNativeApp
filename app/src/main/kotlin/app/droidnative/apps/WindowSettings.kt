package app.droidnative.apps

import android.content.Context
import android.os.Process
import android.util.DisplayMetrics
import android.view.WindowManager
import app.droidnative.env.EnvPaths
import app.droidnative.env.Prefs
import java.io.File

/**
 * Settings for one window, resolved from the process that owns it: the
 * application DroidNative launched (found through the parent chain), the
 * child process policy of that application, and the global settings.
 */
data class WindowSettings(
    val appId: String?,
    val renderFactor: Float,
    val mouseMode: MouseMode,
    val rotation: Rotation,
) {
    companion object {
        /** Settings that apply to a window of client process [pid]. */
        fun forClient(context: Context, pid: Int): WindowSettings {
            val prefs = Prefs(context)
            val store = AppSettingsStore(context)
            val app = resolveApp(context, pid)?.let { store.get(it) to it }
            val s = app?.first ?: AppSettings()
            val short = shortSide(context)
            val res = s.resolution ?: prefs.resolution
            return WindowSettings(
                appId = app?.second,
                renderFactor = res?.let { Resolution.factor(it, short) } ?: 1f,
                mouseMode = s.mouseMode ?: prefs.mouseMode,
                rotation = s.rotation ?: Rotation.AUTO,
            )
        }

        /**
         * Desktop id whose settings apply to [pid]: the launched application
         * the process descends from, adjusted by that application's
         * [ChildPolicy]; null for the global settings.
         */
        fun resolveApp(context: Context, pid: Int): String? {
            if (pid <= 0) return null
            val paths = EnvPaths(context)
            val store = AppSettingsStore(context)
            var p = pid
            var depth = 0
            while (p > 1 && depth < 64) {
                val id = AppLaunches.appOf(paths, p)
                if (id != null) {
                    if (depth == 0) return id
                    val own = listedAppOf(context, pid)
                    return when (store.get(id).children) {
                        ChildPolicy.ALL -> id
                        ChildPolicy.UNLISTED -> own ?: id
                        ChildPolicy.NONE -> own
                    }
                }
                p = parentOf(p) ?: break
                depth++
            }
            // Not started by DroidNative (session daemons, ...): its own entry if listed
            return listedAppOf(context, pid)
        }

        /** Listed application whose command is the program of [pid]. */
        private fun listedAppOf(context: Context, pid: Int): String? {
            val prog = programOf(pid) ?: return null
            return LinuxApps.programIndex(context)[prog]
        }

        private fun parentOf(pid: Int): Int? = try {
            // comm may contain spaces and parentheses: fields after the last ')'
            val stat = File("/proc/$pid/stat").readText()
            stat.substring(stat.lastIndexOf(')') + 2).split(' ')[1].toInt()
                .takeIf { it != Process.myPid() }
        } catch (_: Exception) {
            null
        }

        // Basename of the program, skipping interpreters (python3 script.py)
        internal fun programOf(pid: Int): String? = try {
            val argv = File("/proc/$pid/cmdline").readText().split('\u0000').filter { it.isNotEmpty() }
            val first = argv.firstOrNull()?.substringAfterLast('/') ?: return null
            if (first.startsWith("python") || first == "perl" || first == "sh" || first == "bash")
                argv.getOrNull(1)?.takeIf { !it.startsWith("-") }?.substringAfterLast('/') ?: first
            else first
        } catch (_: Exception) {
            null
        }

        fun shortSide(context: Context): Int {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
            return minOf(dm.widthPixels, dm.heightPixels)
        }
    }
}

/**
 * Processes DroidNative launched: the launch shell writes the desktop id to
 * /run/droidnative/apps/<pid> before it execs the application (same pid).
 */
object AppLaunches {
    fun dir(paths: EnvPaths) = File(paths.runDir, "apps")

    fun appOf(paths: EnvPaths, pid: Int): String? =
        File(dir(paths), pid.toString()).takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    /** Forgets processes that have ended. */
    fun prune(paths: EnvPaths) {
        val files = dir(paths).listFiles() ?: return
        for (f in files) {
            val pid = f.name.toIntOrNull() ?: continue
            if (!File("/proc/$pid").exists()) f.delete()
        }
    }
}
