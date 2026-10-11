package app.droidnative.env

import app.droidnative.devices.Devices
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds and runs proot command lines for the Void guest.
 *
 * proot and its loader are executed from nativeLibraryDir: apps targeting
 * API 29+ may not execute files in their data directory
 * (docs/architecture.md). Argument set follows proot-distro's tested defaults.
 */
class Proot(private val paths: EnvPaths) {

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean) {
        val ok get() = exitCode == 0 && !timedOut
    }

    /**
     * Guest environment applied to every command (before [extraEnv]).
     * [root] selects the fake root account (package management) instead of
     * the unprivileged guest user.
     */
    fun guestEnv(root: Boolean = false): Map<String, String> = baseEnv(root) + gpuEnv()

    // Turnip hides Vulkan 1.1+ on Adreno without hardware multiview (a610,
    // a702) unless noconform is set; on other GPUs the flag changes nothing.
    private fun gpuEnv(): Map<String, String> =
        if (File("/dev/kgsl-3d0").exists()) mapOf(
            "TU_DEBUG" to "noconform",
            // Flatpak: use our GL extension (Turnip + Zink) instead of GL.default
            "FLATPAK_GL_DRIVERS" to "droidnative",
        ) else emptyMap()

    private fun baseEnv(root: Boolean): Map<String, String> = linkedMapOf(
        "HOME" to home(root),
        "USER" to if (root) "root" else EnvPaths.GUEST_USER,
        "LOGNAME" to if (root) "root" else EnvPaths.GUEST_USER,
        // Interactive shells (terminal) get bash; scripts keep #!/bin/sh
        "SHELL" to if (root || !File(paths.rootfs, "bin/bash").exists()) "/bin/sh" else "/bin/bash",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TMPDIR" to "/tmp",
        "XDG_RUNTIME_DIR" to EnvPaths.GUEST_RUN_DIR,
        "WAYLAND_DISPLAY" to EnvPaths.GUEST_WAYLAND_DISPLAY,
        // Rootless Xwayland started by session.sh (dn-xwm)
        "DISPLAY" to ":0",
        "XDG_SESSION_TYPE" to "wayland",
        // Session bus started by assets/guest/session.sh
        "DBUS_SESSION_BUS_ADDRESS" to "unix:path=${EnvPaths.GUEST_RUN_DIR}/bus",
    )

    private fun home(root: Boolean) = if (root) "/root" else EnvPaths.GUEST_HOME

    fun command(
        guestArgv: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        cwd: String? = null,
        extraBinds: List<Pair<String, String>> = emptyList(),
        root: Boolean = false,
    ): List<String> {
        val args = ArrayList<String>()
        args += paths.proot.path
        args += listOf("--kill-on-exit", "--link2symlink", "--sysvipc", "-L")
        // Both identities are fiction on top of the app's uid: -0 answers
        // "root" (xbps needs it), --change-id the guest user
        args += if (root) "-0" else "--change-id=${EnvPaths.GUEST_UID}:${EnvPaths.GUEST_UID}"
        args += "--rootfs=${paths.rootfs.canonicalPath}"
        // The user's home only exists once setup.sh has created the account
        val dir = cwd ?: home(root).takeIf { File(paths.rootfs, it.trimStart('/')).isDirectory } ?: "/"
        args += "--cwd=$dir"
        for ((src, dst) in binds() + extraBinds)
            args += if (src == dst) "--bind=$src" else "--bind=$src:$dst"

        // env -i gives the guest a clean environment independent of Android's
        args += "/usr/bin/env"
        args += "-i"
        for ((k, v) in guestEnv(root) + extraEnv)
            args += "$k=$v"
        args += guestArgv
        return args
    }

    private fun binds(): List<Pair<String, String>> {
        val b = ArrayList<Pair<String, String>>()
        b += "/dev" to "/dev"
        b += "/proc" to "/proc"
        b += "/sys" to "/sys"
        b += "/dev/urandom" to "/dev/random"
        b += "/proc/self/fd" to "/dev/fd"
        b += "/proc/self/fd/0" to "/dev/stdin"
        b += "/proc/self/fd/1" to "/dev/stdout"
        b += "/proc/self/fd/2" to "/dev/stderr"
        // Android hides these from apps; the guest gets plausible stand-ins
        for ((name, guest) in FakeSysdata.FILES)
            b += File(paths.sysdataDir, name).path to guest
        b += File(paths.sysdataDir, "empty").path to "/sys/fs/selinux"
        b += File(paths.filesDir, "shm").path to "/dev/shm"
        b += paths.runDir.path to EnvPaths.GUEST_RUN_DIR
        b += paths.localRepo.path to EnvPaths.GUEST_LOCAL_REPO
        for (mount in StorageMounts.active(paths.appContext))
            b += mount.host.path to mount.guest
        b += Devices.binds(paths)
        return b
    }

    fun processBuilder(
        guestArgv: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        cwd: String? = null,
        root: Boolean = false,
    ): ProcessBuilder {
        paths.ensureDirs()
        File(paths.filesDir, "shm").mkdirs()
        FakeSysdata.write(paths.sysdataDir)
        val pb = ProcessBuilder(command(guestArgv, extraEnv, cwd, root = root))
        val env = pb.environment()
        env.clear()
        env["PROOT_LOADER"] = paths.prootLoader.path
        if (paths.prootLoader32.isFile)
            env["PROOT_LOADER_32"] = paths.prootLoader32.path
        env["PROOT_TMP_DIR"] = paths.prootTmp.path
        // One place for the files behind emulated hard links; proot keeps it
        // reachable inside sandboxes built by bubblewrap/Flatpak.
        // Canonical: proot resolves the rootfs path (/data/user/0 is a symlink
        // to /data/data inside the app), and link targets must match it.
        val l2s = File(paths.rootfs.canonicalFile, ".l2s")
        l2s.mkdirs()
        env["PROOT_L2S_DIR"] = l2s.path
        env.putAll(Devices.prootEnv(paths))
        env["PATH"] = "/system/bin"
        env["ANDROID_ROOT"] = "/system"
        env["ANDROID_DATA"] = "/data"
        return pb
    }

    fun start(guestArgv: List<String>, extraEnv: Map<String, String> = emptyMap(),
              root: Boolean = false): Process =
        processBuilder(guestArgv, extraEnv, root = root).redirectErrorStream(true).start()

    /**
     * Runs a guest command to completion and returns its combined output,
     * capped at [maxOutput] bytes (the rest is drained and dropped).
     */
    fun run(
        guestArgv: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000,
        maxOutput: Int = 256 * 1024,
        onLine: ((String) -> Unit)? = null,
        root: Boolean = false,
    ): Result {
        val proc = start(guestArgv, extraEnv, root)
        proc.outputStream.close()
        val out = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    onLine?.invoke(line)
                    if (out.length < maxOutput) out.append(line).append('\n')
                }
            }
        }
        reader.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) proc.destroyForcibly()
        reader.join(5_000)
        val code = if (finished) proc.exitValue() else -1
        return Result(code, out.toString(), !finished)
    }

    /** Convenience: run a shell snippet in the guest. */
    fun sh(script: String, extraEnv: Map<String, String> = emptyMap(), timeoutMs: Long = 120_000,
           onLine: ((String) -> Unit)? = null, root: Boolean = false): Result =
        run(listOf("/bin/sh", "-c", script), extraEnv, timeoutMs, onLine = onLine, root = root)
}
