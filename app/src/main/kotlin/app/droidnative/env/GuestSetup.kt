package app.droidnative.env

import android.content.Context
import android.net.ConnectivityManager
import app.droidnative.BuildConfig
import java.io.File
import java.io.IOException
import java.net.Inet4Address

/**
 * First-boot configuration of the guest: host-side files (DNS, the
 * DroidNative package repository and its signing key, profile), then
 * assets/guest/setup.sh inside proot (package installation, keymap).
 */
class GuestSetup(
    private val context: Context,
    private val paths: EnvPaths,
    private val proot: Proot,
) {
    /** Done for the setup scripts and package repository of this APK. */
    val isDone get() = paths.setupDone.isFile && paths.setupDone.readText().trim() == setupId()

    /**
     * Identifies what the guest was set up with: changes when an app update
     * ships a different setup.sh or package repository, which makes the
     * (idempotent) setup run again. Package updates come from the
     * repository itself (Xbps.update).
     */
    fun setupId(): String = cachedId ?: computeSetupId().also { cachedId = it }

    private fun computeSetupId(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (asset in listOf("guest/setup.sh", "guest/setup-user.sh")) {
            try {
                context.assets.open(asset).use { md.update(it.readBytes()) }
            } catch (_: IOException) {
            }
        }
        md.update(BuildConfig.XBPS_REPO.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Writes host-side config; cheap, runs on every environment start. */
    fun writeHostFiles() {
        val etc = File(paths.rootfs, "etc")
        writeResolvConf(File(etc, "resolv.conf"))
        File(etc, "hosts").writeText(
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(etc, "hostname").writeText("droidnative\n")

        // Listed before Void's repositories (/usr/share/xbps.d), so its
        // packages win. xbps only installs from signed remote repositories
        // and cannot ask whether to trust a key here, hence the shipped keys.
        File(etc, "xbps.d").mkdirs()
        File(etc, "xbps.d/10-droidnative.conf")
            .writeText("repository=${BuildConfig.XBPS_REPO}\n")
        File(etc, "xbps.d/10-droidnative-local.conf").delete()   // bundled repo of older versions
        val keys = File(paths.rootfs, "var/db/xbps/keys")
        keys.mkdirs()
        for (name in context.assets.list("xbps-keys").orEmpty())
            if (name.endsWith(".plist")) copyAsset("xbps-keys/$name", File(keys, name))

        File(etc, "profile.d").mkdirs()
        copyAsset("guest/profile.sh", File(etc, "profile.d/droidnative.sh"))
        val libexec = File(paths.rootfs, "usr/libexec/droidnative")
        libexec.mkdirs()
        for (script in listOf("setup.sh", "setup-user.sh", "session.sh")) {
            copyAsset("guest/$script", File(libexec, script))
            File(libexec, script).setExecutable(true)
        }
        File(etc, "droidnative").mkdirs()
        for (conf in listOf("pulse.pa", "dbus-session.conf"))
            copyAsset("guest/$conf", File(etc, "droidnative/$conf"))
        // Mount points for StorageMounts (proot wants them to exist)
        File(paths.rootfs, "media/android").delete()   // old name; only goes if empty
        for (mount in StorageMounts.volumes(context))
            File(paths.rootfs, mount.guest.trimStart('/')).mkdirs()
        File(paths.rootfs, "run/droidnative").mkdirs()
        removeLocalRepo()
    }

    /** Runs the guest part of the setup. Needs network. Returns the output. */
    @Throws(IOException::class)
    fun run(onLine: ((String) -> Unit)? = null): Proot.Result {
        writeHostFiles()
        val log = File(paths.logDir, "setup.log")
        log.parentFile?.mkdirs()
        val arch = VoidArch.current().voidName
        val result = log.bufferedWriter().use { w ->
            val sink: (String) -> Unit = { line ->
                w.write(line); w.newLine(); w.flush()
                onLine?.invoke(line)
            }
            // Packages and the user account as root, per-user settings as the user
            val system = proot.run(listOf("/usr/libexec/droidnative/setup.sh", arch),
                timeoutMs = 60 * 60_000L, onLine = sink, root = true)
            if (!system.ok) system
            else proot.run(listOf("/usr/libexec/droidnative/setup-user.sh", arch),
                timeoutMs = 10 * 60_000L, onLine = sink)
        }
        if (result.ok)
            paths.setupDone.writeText(setupId() + "\n")
        return result
    }

    private fun writeResolvConf(file: File) {
        val servers = ArrayList<String>()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.forEach { addr ->
                // Private DNS / link-local v6 servers do not work from glibc
                if (addr is Inet4Address) servers += addr.hostAddress ?: return@forEach
            }
        } catch (_: SecurityException) {
        }
        if (servers.isEmpty()) servers += listOf("1.1.1.1", "9.9.9.9")
        file.parentFile?.mkdirs()
        // May be a (dangling) symlink in the rootfs; replace it with a file
        if (AndroidFileOps.lexists(file)) file.delete()
        file.writeText(servers.joinToString("") { "nameserver $it\n" })
    }

    /** Older versions copied a package repository from the APK; it goes. */
    private fun removeLocalRepo() {
        val legacy = File(paths.filesDir, "repo")
        if (legacy.isDirectory) legacy.deleteRecursively()
        File(paths.rootfs, "opt/droidnative/repo").delete()   // mount point, only if empty
        File(paths.rootfs, "opt/droidnative").delete()
    }

    private fun copyAsset(asset: String, dest: File) {
        context.assets.open(asset).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    companion object {
        // Assets cannot change while the process lives
        @Volatile private var cachedId: String? = null
    }
}
