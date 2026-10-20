package app.droidnative.env

import android.content.Context
import java.io.File

/**
 * Disk images: folders DroidNative/images/<name> on the Android storage
 * volumes, mounted in Linux at /mnt/<name> (proot --image). An image keeps
 * what the storage below can't: permissions, owners, symlinks, hard links,
 * upper and lower case names; programs in it can run. Each one is a SQLite
 * database of its files (dnimage.db) plus their contents under UUID names
 * (data/), see the proot fork's extension/dn_storage/image.c.
 *
 * Images need the all-files access of [StorageMounts]. proot sets up an
 * empty folder as a new image on the next start.
 */
object DiskImages {
    const val FOLDER = "DroidNative/images"
    const val GUEST_BASE = "/mnt"

    data class Image(val name: String, val dir: File, val volume: StorageMounts.Mount) {
        val guest get() = "$GUEST_BASE/$name"
    }

    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun validName(name: String) = NAME.matches(name)

    /** Images on the volumes Linux sees now; the first of a name wins. */
    fun active(context: Context): List<Image> {
        val out = ArrayList<Image>()
        for (volume in StorageMounts.active(context)) {
            val dirs = File(volume.host, FOLDER).listFiles() ?: continue
            for (dir in dirs.sortedBy { it.name }) {
                if (!dir.isDirectory || !validName(dir.name) || out.any { it.name == dir.name }) continue
                out += Image(dir.name, dir, volume)
            }
        }
        return out
    }

    /** Creates the folder of a new image; null on success, else why not. */
    fun create(context: Context, volume: StorageMounts.Mount, name: String): String? {
        if (!validName(name)) return "invalid name"
        if (active(context).any { it.name == name }) return "exists"
        val dir = File(volume.host, "$FOLDER/$name")
        return if (dir.mkdirs()) null else "cannot create ${dir.path}"
    }

    /** Deletes an image with all its files. */
    fun delete(image: Image): Boolean = image.dir.deleteRecursively()

    /** Bytes used by the files of [image] (its database and contents). */
    fun size(image: Image): Long = image.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
