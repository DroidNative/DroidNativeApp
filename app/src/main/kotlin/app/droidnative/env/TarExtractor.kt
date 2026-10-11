package app.droidnative.env

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Minimal tar reader (ustar, GNU long names, pax path/linkpath) for unpacking
 * the Void rootfs. Android forbids hard links in app storage, so hard links
 * become copies of their target. Device nodes and FIFOs are skipped.
 */
class TarExtractor(private val fs: FileOps) {

    /** File system operations the platform must provide. */
    interface FileOps {
        fun symlink(target: String, link: File)
        fun chmod(file: File, mode: Int)
        /** True if anything (including a dangling symlink) exists at f. */
        fun lexists(f: File): Boolean
    }

    data class Stats(var files: Int = 0, var dirs: Int = 0, var symlinks: Int = 0,
                     var hardlinks: Int = 0, var skipped: Int = 0)

    private class Header(
        val name: String, val mode: Int, val size: Long, val type: Char,
        val linkName: String,
    )

    fun extract(input: InputStream, dest: File, onEntry: ((String) -> Unit)? = null): Stats {
        val stats = Stats()
        val destCanon = dest.canonicalFile
        destCanon.mkdirs()
        val dirModes = ArrayList<Pair<File, Int>>()
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        val block = ByteArray(512)

        while (true) {
            if (!readFully(input, block)) break
            if (block.all { it.toInt() == 0 }) break   // end-of-archive marker
            val h = parseHeader(block)

            when (h.type) {
                'L' -> { longName = readString(input, h.size); continue }
                'K' -> { longLink = readString(input, h.size); continue }
                'x' -> {
                    val pax = parsePax(readBytes(input, h.size))
                    paxPath = pax["path"]
                    paxLink = pax["linkpath"]
                    continue
                }
                'g' -> { skip(input, padded(h.size)); continue }
            }

            val name = (paxPath ?: longName ?: h.name).removePrefix("./").trimEnd('/')
            val link = paxLink ?: longLink ?: h.linkName
            longName = null; longLink = null; paxPath = null; paxLink = null

            if (name.isEmpty() || name == ".") {
                skip(input, padded(h.size))
                continue
            }
            val target = safeChild(destCanon, name)
            onEntry?.invoke(name)

            when (h.type) {
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    if (fs.lexists(target)) target.delete()
                    target.outputStream().use { out -> copyExact(input, out, h.size) }
                    skip(input, padded(h.size) - h.size)
                    fs.chmod(target, h.mode)
                    stats.files++
                    continue
                }
                '5' -> {
                    target.mkdirs()
                    // Apply after extraction: read-only dirs would block their content
                    dirModes.add(target to h.mode)
                    stats.dirs++
                }
                '2' -> {
                    target.parentFile?.mkdirs()
                    if (fs.lexists(target)) target.delete()
                    fs.symlink(link, target)
                    stats.symlinks++
                }
                '1' -> {
                    val src = safeChild(destCanon, link.removePrefix("./"))
                    target.parentFile?.mkdirs()
                    if (src.isFile) {
                        if (fs.lexists(target)) target.delete()
                        src.copyTo(target)
                        fs.chmod(target, h.mode)
                        stats.hardlinks++
                    } else {
                        stats.skipped++
                    }
                }
                else -> stats.skipped++   // '3' char dev, '4' block dev, '6' fifo
            }
            skip(input, padded(h.size))
        }

        for ((dir, mode) in dirModes.asReversed())
            fs.chmod(dir, mode or 0b111_000_000)   // owner keeps rwx inside proot
        return stats
    }

    /* Header parsing */

    private fun parseHeader(b: ByteArray): Header {
        val name = cString(b, 0, 100)
        val mode = parseOctal(b, 100, 8).toInt()
        val size = parseSize(b, 124, 12)
        val type = b[156].toInt().toChar()
        val linkName = cString(b, 157, 100)
        val magic = cString(b, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(b, 345, 155) else ""
        val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
        return Header(full, mode, size, type, linkName)
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt().toChar()
            if (c == ' ' || c == '\u0000') { if (v != 0L) break else continue }
            if (c !in '0'..'7') break
            v = v * 8 + (c - '0')
        }
        return v
    }

    // GNU base-256 encoding for sizes >= 8 GiB
    private fun parseSize(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
            return v
        }
        return parseOctal(b, off, len)
    }

    // Records are "<len> <key>=<value>\n"; values may be binary (xattrs)
    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < bytes.size) {
            var sp = i
            while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
            val len = String(bytes, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > bytes.size || sp + 1 > i + len - 1) break
            val record = String(bytes, sp + 1, i + len - 1 - (sp + 1), Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) {
                val key = record.substring(0, eq)
                if (key == "path" || key == "linkpath") out[key] = record.substring(eq + 1)
            }
            i += len
        }
        return out
    }

    /* Stream helpers */

    private fun padded(size: Long) = (size + 511) / 512 * 512

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("truncated tar header")
            }
            off += n
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        if (size > 16L shl 20) throw IOException("tar metadata entry too large")
        val buf = ByteArray(size.toInt())
        if (!readFully(input, buf)) throw EOFException("truncated tar entry")
        skip(input, padded(size) - size)
        return buf
    }

    private fun readString(input: InputStream, size: Long): String {
        val buf = readBytes(input, size)
        return cString(buf, 0, buf.size)
    }

    private fun copyExact(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated file data")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated tar padding")
            left -= n
        }
    }

    // Rejects entries escaping the destination ("../", absolute paths)
    private fun safeChild(root: File, name: String): File {
        val clean = name.trimStart('/')
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("unsafe tar entry: $name")
        return File(root, parts.joinToString("/"))
    }
}
