package app.droidnative.env

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.tukaani.xz.XZInputStream

/**
 * Downloads (or picks up a pushed copy of) the Void rootfs tarball, verifies
 * its SHA-256 and unpacks it into [EnvPaths.rootfs].
 *
 * Pushed copies are looked up first, so tests can avoid the ~60 MB download:
 *   <external files dir>/<tarball>   (adb push without extra permissions)
 *   /data/local/tmp/<tarball>
 */
class RootfsInstaller(private val context: Context, private val paths: EnvPaths) {

    fun interface Progress {
        fun update(stage: String, done: Long, total: Long)
    }

    val isInstalled get() = paths.rootfsReady.isFile

    @Throws(IOException::class)
    fun install(arch: VoidArch = VoidArch.current(), progress: Progress? = null): File {
        paths.ensureDirs()
        val name = arch.rootfsName()
        val expected = fetchExpectedSha256(name)
        val tarball = findLocalTarball(name)?.also {
            progress?.update("using local $it", 0, 0)
        } ?: download(arch.rootfsUrl(), File(paths.downloads, name), progress)

        progress?.update("verifying", 0, 0)
        if (expected != null) {
            val actual = sha256(tarball)
            if (!actual.equals(expected, ignoreCase = true))
                throw IOException("checksum mismatch for $name: $actual != $expected")
        }

        val tmp = File(paths.filesDir, "rootfs.partial")
        deleteTree(tmp, paths.filesDir)
        val total = tarball.length()
        var lastReport = 0L
        val counting = CountingInputStream(BufferedInputStream(tarball.inputStream(), 1 shl 16))
        XZInputStream(counting).use { xz ->
            TarExtractor(AndroidFileOps).extract(xz, tmp) {
                if (counting.count - lastReport > 1 shl 20) {
                    lastReport = counting.count
                    progress?.update("extracting", counting.count, total)
                }
            }
        }
        deleteTree(paths.rootfs, paths.filesDir)
        if (!tmp.renameTo(paths.rootfs))
            throw IOException("cannot move rootfs into place")
        if (tarball.parentFile == paths.downloads)
            tarball.delete()
        paths.rootfsReady.writeText("$name\n")
        return paths.rootfs
    }

    private fun findLocalTarball(name: String): File? {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, name) },
            File("/data/local/tmp", name),
            File(paths.downloads, name),
        )
        return candidates.firstOrNull { it.isFile && it.canRead() && it.length() > 0 }
    }

    // null when offline and a local tarball is used: integrity is then only
    // checked by the xz/tar decoders
    private fun fetchExpectedSha256(name: String): String? = try {
        parseSha256List(httpGet(VoidArch.SHA256_URL).decodeToString(), name)
    } catch (e: IOException) {
        null
    }

    private fun download(url: String, dest: File, progress: Progress?): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200)
                throw IOException("HTTP ${conn.responseCode} for $url")
            val total = conn.contentLengthLong
            val part = File(dest.path + ".part")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 1 shl 20) {
                            last = done
                            progress?.update("downloading", done, total)
                        }
                    }
                }
            }
            if (!part.renameTo(dest)) throw IOException("cannot rename download")
            return dest
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private class CountingInputStream(private val inner: java.io.InputStream) :
        java.io.FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        /** Accepts BSD ("SHA256 (name) = hash", used by Void) and GNU lines. */
        fun parseSha256List(text: String, name: String): String? {
            val bsd = Regex("""^SHA256 \((.+)\) = ([0-9a-fA-F]{64})$""")
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                bsd.matchEntire(line)?.let { m ->
                    if (m.groupValues[1] == name) return m.groupValues[2]
                }
                val parts = line.split(Regex("\\s+"))
                if (parts.size == 2 && parts[0].length == 64 && parts[1].trimStart('*') == name)
                    return parts[0]
            }
            return null
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Recursive delete that never follows symlinks (the rootfs is full of
         * absolute links like /usr/lib that point at the host from here).
         * Refuses anything outside the app's own data.
         */
        fun deleteTree(root: File, within: File) {
            val base = within.canonicalPath.trimEnd('/')
            // Canonicalise the parent only: root itself may be a symlink, and
            // /data/user/0 is a symlink to /data/data on some devices
            val parent = root.absoluteFile.parentFile?.canonicalPath ?: "/"
            val path = "$parent/${root.name}"
            require(base.length > 1 && path.startsWith("$base/") && path.length > base.length + 1) {
                "refusing to delete $path (not inside $base)"
            }
            deleteRec(root)
        }

        private fun deleteRec(f: File) {
            val st = try { Os.lstat(f.path) } catch (e: ErrnoException) { return }
            if (android.system.OsConstants.S_ISDIR(st.st_mode)) {
                // Make sure we may list and modify the directory
                try { Os.chmod(f.path, 0b111_000_000 or (st.st_mode and 0b111_111)) } catch (_: ErrnoException) {}
                f.list()?.forEach { deleteRec(File(f, it)) }
            }
            f.delete()
        }
    }
}

/** [TarExtractor.FileOps] on Android, through android.system.Os. */
object AndroidFileOps : TarExtractor.FileOps {
    override fun symlink(target: String, link: File) = Os.symlink(target, link.path)

    override fun chmod(file: File, mode: Int) {
        // Keep the owner able to read/write everything; proot fakes the rest
        Os.chmod(file.path, (mode and 0b111_111_111) or 0b110_000_000)
    }

    override fun lexists(f: File): Boolean = try {
        Os.lstat(f.path); true
    } catch (e: ErrnoException) {
        false
    }
}
