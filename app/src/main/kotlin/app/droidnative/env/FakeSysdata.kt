package app.droidnative.env

import java.io.File

/**
 * Stand-ins for /proc files that Android denies to apps (SELinux), bound
 * over the real ones by [Proot]. Same set as proot-distro's sysdata.
 */
object FakeSysdata {
    /** host file name in sysdataDir -> guest path */
    val FILES = listOf(
        "loadavg" to "/proc/loadavg",
        "stat" to "/proc/stat",
        "uptime" to "/proc/uptime",
        "version" to "/proc/version",
        "vmstat" to "/proc/vmstat",
        "cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
        "max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
        // bubblewrap reads these when setting up user namespaces
        "overflowuid" to "/proc/sys/kernel/overflowuid",
        "overflowgid" to "/proc/sys/kernel/overflowgid",
        // libpci (pciutils) ends the whole process with exit(1) when it
        // finds neither /sys/bus/pci nor /proc/bus/pci; Chromium probes the
        // GPU through it at start and again after a minute. An empty
        // directory listing no devices keeps it alive.
        "pci" to "/proc/bus/pci",
    )

    fun write(dir: File) {
        dir.mkdirs()
        File(dir, "empty").mkdirs()
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val uptimeSec = android.os.SystemClock.elapsedRealtime() / 1000.0

        // Files are only rewritten when their content differs
        writeIfChanged(File(dir, "loadavg"), "0.12 0.07 0.02 2/165 765\n")
        writeIfChanged(File(dir, "stat"), fakeStat(cpus))
        writeIfChanged(File(dir, "uptime"), "%.2f %.2f\n".format(uptimeSec, uptimeSec * cpus * 0.9))
        writeIfChanged(File(dir, "version"),
            "Linux version ${System.getProperty("os.version") ?: "5.4.0"} (droidnative@localhost) " +
                "(gcc version 13.2.0) #1 SMP PREEMPT\n")
        writeIfChanged(File(dir, "vmstat"), FAKE_VMSTAT)
        writeIfChanged(File(dir, "cap_last_cap"), "40\n")
        writeIfChanged(File(dir, "max_user_watches"), "4096\n")
        writeIfChanged(File(dir, "overflowuid"), "65534\n")
        writeIfChanged(File(dir, "overflowgid"), "65534\n")
        File(dir, "pci").mkdirs()
        writeIfChanged(File(dir, "pci/devices"), "")
    }

    private fun fakeStat(cpus: Int): String {
        val sb = StringBuilder()
        val row = "1957 0 2877 93280 262 342 254 0 0 0"
        sb.append("cpu  ").append(row).append('\n')
        for (i in 0 until cpus) sb.append("cpu$i ").append(row).append('\n')
        sb.append("intr 1\nctxt 1\nbtime 1700000000\nprocesses 1\n")
        sb.append("procs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
        return sb.toString()
    }

    private fun writeIfChanged(f: File, content: String) {
        if (!f.isFile || f.readText() != content) f.writeText(content)
    }

    private const val FAKE_VMSTAT = "nr_free_pages 146031\nnr_zone_inactive_anon 196744\n" +
        "nr_zone_active_anon 301503\nnr_zone_inactive_file 2457066\n" +
        "nr_zone_active_file 729742\nnr_zone_unevictable 164\nnr_zone_write_pending 8\n" +
        "nr_mlock 34\nnr_bounce 0\nnr_zspages 0\nnr_free_cma 0\npgpgin 0\npgpgout 0\n" +
        "pswpin 0\npswpout 0\n"
}
