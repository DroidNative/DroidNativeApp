package app.droidnative.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import app.droidnative.R
import app.droidnative.env.EnvPaths
import app.droidnative.env.Proot
import app.droidnative.env.VoidArch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * AppImages installed with `dn-appimage` (droidnative-tools): unpacked
 * AppDirs in the guest's ~/.local/share/droidnative/appimages, each with a
 * desktop entry dn-appimage-<id>.desktop. Listing reads the rootfs
 * directly; changes go through dn-appimage so the guest tool stays the
 * only writer.
 */
class AppImages(context: Context) {
    private val app = context.applicationContext
    private val paths = EnvPaths(app)
    private val store = File(paths.rootfs, STORE)

    /** Installed AppImage [id] with its desktop entry; [customIcon]: own icon set. */
    class Item(val id: String, val entry: DesktopEntry, val customIcon: Boolean)

    fun list(): List<Item> {
        val dirs = store.listFiles() ?: return emptyList()
        val custom = File(store, ".custom")
        return dirs.filter { !it.name.startsWith(".") && File(it, "AppRun").exists() }.map { dir ->
            val id = dir.name
            val entry = DesktopEntry.parse(File(paths.rootfs, "$APPLICATIONS/dn-appimage-$id.desktop"))
                ?: DesktopEntry(id, id, "dn-appimage run $id", "", false)
            Item(id, entry, ICON_TYPES.any { File(custom, "$id.$it").exists() })
        }.sortedBy { it.entry.name.lowercase() }
    }

    /** Own title; empty restores the AppImage's. */
    fun rename(id: String, name: String) = tool("rename", id, name.trim())

    /** Own icon from [bitmap] (made square, at most 256 px), null restores the AppImage's. */
    fun setIcon(id: String, bitmap: Bitmap?) {
        if (bitmap == null) return tool("icon", id)
        val tmp = File(paths.rootfs, "tmp/dn-appimage-icon-$id.png")
        try {
            tmp.parentFile?.mkdirs()
            tmp.outputStream().use { scaled(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it) }
            tool("icon", id, "/tmp/${tmp.name}")
        } finally {
            tmp.delete()
        }
    }

    fun remove(id: String) = tool("remove", id)

    // Runs dn-appimage; throws with its message on failure
    private fun tool(vararg args: String) {
        val r = Proot(paths).run(listOf("dn-appimage") + args, timeoutMs = 300_000)
        LinuxApps.invalidate()
        if (!r.ok) throw IOException(errorOf(r))
    }

    // At most ICON_SIZE, centred on a transparent square like theme icons
    private fun scaled(b: Bitmap): Bitmap {
        val side = minOf(max(b.width, b.height), ICON_SIZE)
        val f = side.toFloat() / max(b.width, b.height)
        val w = max(1, (b.width * f).toInt())
        val h = max(1, (b.height * f).toInt())
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, null, Rect((side - w) / 2, (side - h) / 2, (side + w) / 2, (side + h) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Progress of the install running in the background, if any. */
    sealed class Install(val name: String) {
        /** [fraction] of the file copied into the guest, -1 if unknown. */
        class Copying(name: String, val fraction: Float) : Install(name)
        class Unpacking(name: String) : Install(name)
        class Done(name: String, val id: String) : Install(name)
        class Failed(name: String, val message: String) : Install(name)
    }

    companion object {
        private const val TAG = "dn-appimage"
        private const val STORE = "home/user/.local/share/droidnative/appimages"
        private const val APPLICATIONS = "home/user/.local/share/applications"
        private const val IMPORT = "tmp/dn-appimage-import"
        private const val ICON_SIZE = 256
        private val ICON_TYPES = listOf("png", "svg", "xpm", "jpg")

        private val _install = MutableStateFlow<Install?>(null)
        val install: StateFlow<Install?> = _install.asStateFlow()

        /** Clears a finished or failed install from [install]. */
        fun dismiss() {
            if (_install.value is Install.Done || _install.value is Install.Failed) _install.value = null
        }

        /** Bitmap of an image document, sampled down to about icon size; null if Android cannot read it. */
        fun readImage(context: Context, uri: Uri): Bitmap? = try {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ICON_SIZE) sample *= 2
            cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) {
            null
        }

        /**
         * Installs the AppImage at [uri] in the background: copies it into
         * the guest's /tmp and lets `dn-appimage install --move` unpack it.
         * Returns false if an install is already running.
         */
        @Synchronized
        fun install(context: Context, uri: Uri): Boolean {
            val cur = _install.value
            if (cur is Install.Copying || cur is Install.Unpacking) return false
            val app = context.applicationContext
            val (name, size) = describe(app, uri)
            _install.value = Install.Copying(name, -1f)
            thread(name = "appimage-install") {
                _install.value = try {
                    Install.Done(name, runInstall(app, uri, name, size))
                } catch (e: Exception) {
                    Log.w(TAG, "install of $name failed", e)
                    Install.Failed(name, e.message ?: e.toString())
                }
            }
            return true
        }

        private fun describe(context: Context, uri: Uri): Pair<String, Long> {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "app.AppImage"
            var size = -1L
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (_: Exception) {}
            return name to size
        }

        private fun runInstall(context: Context, uri: Uri, name: String, size: Long): String {
            val paths = EnvPaths(context)
            val cr = context.contentResolver
            // Header first: refuse other files and other architectures before copying
            val head = ByteArray(20)
            val n = cr.openInputStream(uri)?.use { input ->
                var got = 0
                while (got < head.size) {
                    val r = input.read(head, got, head.size - got)
                    if (r < 0) break
                    got += r
                }
                got
            } ?: throw IOException(context.getString(R.string.appimage_cannot_open, name))
            if (n < head.size || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
                head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte())
                throw IOException(context.getString(R.string.appimage_not_appimage, name))
            val machine = (head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)
            val arch = VoidArch.current()
            if (machine != elfMachine(arch)) {
                // x86 runs emulated when the emulator is installed (dn-appimage emulator_for)
                val emu = emulatorFor(arch, machine)
                if (emu == null)
                    throw IOException(context.getString(R.string.appimage_wrong_arch, name, machineName(machine),
                        arch.voidName))
                if (!File(paths.rootfs, "usr/bin/$emu").exists())
                    throw IOException(context.getString(R.string.appimage_needs_emulator, name, machineName(machine),
                        emu))
            }
            // Image plus unpacked tree, which is usually about twice as large
            if (size > 0) {
                val free = StatFs(paths.rootfs.path).availableBytes
                if (free < size * 3)
                    throw IOException(context.getString(R.string.appimage_no_space, mb(size * 3), mb(free)))
            }

            val dir = File(paths.rootfs, IMPORT)
            dir.mkdirs()
            // dn-appimage takes the id from the file name
            val base = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "app" }
            val file = File(dir, if (base.endsWith(".AppImage", ignoreCase = true)) base else "$base.AppImage")
            try {
                cr.openInputStream(uri)?.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var shown = 0L
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            done += r
                            if (size > 0 && done - shown > size / 100) {
                                shown = done
                                _install.value = Install.Copying(name, done.toFloat() / size)
                            }
                        }
                    }
                } ?: throw IOException(context.getString(R.string.appimage_cannot_open, name))
                _install.value = Install.Unpacking(name)
                val r = Proot(paths).run(listOf("dn-appimage", "install", "--move", "/$IMPORT/${file.name}"),
                    timeoutMs = 30 * 60_000L)
                LinuxApps.invalidate()
                if (!r.ok) throw IOException(errorOf(r))
                val id = r.output.trim().lines().last()
                applyPreset(context, id)
                return id
            } finally {
                file.delete()
            }
        }

        /**
         * Preset "appimage/<name>", name = the AppImage's own desktop file
         * (stable across versions, unlike the id from the file name); its
         * settings go to the entry dn-appimage wrote.
         */
        private fun applyPreset(context: Context, id: String) {
            val dir = File(EnvPaths(context).rootfs, "$STORE/$id")
            val own = dir.listFiles { f -> f.name.endsWith(".desktop") }?.firstOrNull() ?: return
            Presets.refresh(context)
            Presets.postinstall(context, Presets.Source.APPIMAGE, own.name.removeSuffix(".desktop")) { "dn-appimage-$id" }
            LinuxApps.invalidate()
        }

        // e_machine values, as dn-appimage's elf_arch
        private fun elfMachine(arch: VoidArch) = when (arch) {
            VoidArch.AARCH64 -> 183
            VoidArch.ARMV7L -> 40
            VoidArch.X86_64 -> 62
            VoidArch.I686 -> 3
        }

        /** Package whose binfmt rule runs [machine] programs on [arch], if any. */
        private fun emulatorFor(arch: VoidArch, machine: Int) = when {
            arch == VoidArch.AARCH64 && (machine == 62 || machine == 3) -> "box64"
            arch == VoidArch.ARMV7L && machine == 3 -> "box86"
            else -> null
        }

        private fun machineName(m: Int) = when (m) {
            183 -> "aarch64"
            40 -> "32-bit ARM"
            62 -> "x86_64"
            3 -> "i686"
            else -> "machine $m"
        }

        private fun mb(bytes: Long) = bytes / (1024 * 1024)

        // The tool's own message ("dn-appimage: ..."), else the output's end
        private fun errorOf(r: Proot.Result): String {
            if (r.timedOut) return "dn-appimage did not finish in time"
            val lines = r.output.trim().lines()
            return lines.lastOrNull { it.startsWith("dn-appimage: ") }?.removePrefix("dn-appimage: ")
                ?: lines.takeLast(3).joinToString("\n").ifBlank { "dn-appimage failed (exit ${r.exitCode})" }
        }
    }
}
