package app.droidnative

import app.droidnative.env.TarExtractor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Extracts archives made by the system tar (GNU and pax formats). Output goes
 * below build/tmp and is left for inspection (no recursive deletes in tests).
 */
class TarExtractorTest {

    private object JvmFileOps : TarExtractor.FileOps {
        override fun symlink(target: String, link: File) {
            Files.createSymbolicLink(link.toPath(), Paths.get(target))
        }

        override fun chmod(file: File, mode: Int) {
            val perms = HashSet<PosixFilePermission>()
            val all = PosixFilePermission.entries   // OWNER_READ .. OTHERS_EXECUTE
            for (i in 0 until 9) if (mode and (1 shl (8 - i)) != 0) perms += all[i]
            perms += PosixFilePermission.OWNER_READ
            perms += PosixFilePermission.OWNER_WRITE
            Files.setPosixFilePermissions(file.toPath(), perms)
        }

        override fun lexists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun workDir(name: String) =
        File("build/tmp/tar-test/$name-${UUID.randomUUID()}").apply { mkdirs() }

    private fun makeTree(src: File) {
        File(src, "usr/bin").mkdirs()
        File(src, "usr/bin/tool").writeText("#!/bin/sh\necho hi\n")
        File(src, "usr/bin/tool").setExecutable(true)
        val deep = File(src, "usr/share/" + "d".repeat(60) + "/" + "e".repeat(60))
        deep.mkdirs()
        File(deep, "long-file-name-" + "x".repeat(80) + ".txt").writeText("long")
        Files.createSymbolicLink(File(src, "bin").toPath(), Paths.get("usr/bin"))
        Files.createSymbolicLink(File(src, "abs").toPath(), Paths.get("/usr/lib/missing"))
        Files.createLink(File(src, "usr/bin/tool-hard").toPath(), File(src, "usr/bin/tool").toPath())
    }

    private fun tar(src: File, out: File, format: String) {
        val p = ProcessBuilder("tar", "--format=$format", "-cf", out.absolutePath, "-C", src.absolutePath, ".")
            .redirectErrorStream(true).start()
        val msg = p.inputStream.bufferedReader().readText()
        assertEquals(msg, 0, p.waitFor())
    }

    private fun check(format: String) {
        val work = workDir(format)
        val src = File(work, "src").apply { mkdirs() }
        makeTree(src)
        val archive = File(work, "a.tar")
        tar(src, archive, format)
        val dest = File(work, "dest")
        val stats = archive.inputStream().buffered().use { TarExtractor(JvmFileOps).extract(it, dest) }

        assertEquals("#!/bin/sh\necho hi\n", File(dest, "usr/bin/tool").readText())
        assertTrue(File(dest, "usr/bin/tool").canExecute())
        assertEquals("#!/bin/sh\necho hi\n", File(dest, "usr/bin/tool-hard").readText())
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertEquals("/usr/lib/missing", Files.readSymbolicLink(File(dest, "abs").toPath()).toString())
        val deep = File(dest, "usr/share/" + "d".repeat(60) + "/" + "e".repeat(60) +
            "/long-file-name-" + "x".repeat(80) + ".txt")
        assertEquals("long", deep.readText())
        assertEquals(1, stats.hardlinks)
    }

    @Test fun gnuFormat() = check("gnu")
    @Test fun paxFormat() = check("pax")

    /** Optional: extract a real rootfs tarball, path given by DN_ROOTFS_TARBALL */
    @Test
    fun realRootfs() {
        val path = System.getenv("DN_ROOTFS_TARBALL")
        assumeTrue("DN_ROOTFS_TARBALL not set", !path.isNullOrEmpty())
        val dest = File(System.getenv("DN_ROOTFS_OUT") ?: workDir("rootfs").path)
        val stats = org.tukaani.xz.XZInputStream(File(path!!).inputStream().buffered(1 shl 16)).use {
            TarExtractor(JvmFileOps).extract(it, dest)
        }
        println("rootfs: $stats -> $dest")
        assertTrue(File(dest, "usr/bin/xbps-install").isFile)
    }
}

class Sha256ListTest {
    @Test
    fun bsdAndGnuFormats() {
        val h = "3f48e6673ac5907a897d913c97eb96edbfb230162731b4016562c51b3b8f1876"
        val list = "SHA256 (void-x86_64-ROOTFS-20250202.tar.xz) = $h\n" +
            "${"a".repeat(64)}  other.tar.xz\n"
        assertEquals(h, app.droidnative.env.RootfsInstaller.parseSha256List(list, "void-x86_64-ROOTFS-20250202.tar.xz"))
        assertEquals("a".repeat(64), app.droidnative.env.RootfsInstaller.parseSha256List(list, "other.tar.xz"))
        assertEquals(null, app.droidnative.env.RootfsInstaller.parseSha256List(list, "missing.tar.xz"))
    }
}
