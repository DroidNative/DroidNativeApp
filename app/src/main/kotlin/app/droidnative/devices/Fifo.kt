package app.droidnative.devices

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/**
 * Writing end of a FIFO that the guest reads as a device node. Opened only
 * while a reader exists (O_NONBLOCK open fails with ENXIO otherwise), so a
 * reader never gets data that queued up before it opened the device.
 */
class FifoWriter(val file: File) {
    private var fd: FileDescriptor? = null

    /** Creates the FIFO; keeps an existing one, since PRoot identifies devices by inode. */
    fun create() {
        try {
            if (Os.lstat(file.path).st_mode and OsConstants.S_IFMT == OsConstants.S_IFIFO) return
            file.delete()
        } catch (_: ErrnoException) {
        }
        file.parentFile?.mkdirs()
        Os.mkfifo(file.path, 0x1b0 /* 0660 */)
    }

    /** True when a reader has the FIFO open (opens our end on the way). */
    fun hasReader(): Boolean {
        if (fd != null) return true
        return try {
            fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            true
        } catch (_: ErrnoException) {
            false   // ENXIO: nobody reading
        }
    }

    /**
     * Writes all of [data] or nothing that matters: false when there is no
     * reader or the pipe is full (the reader is behind; the data is dropped).
     */
    fun write(data: ByteArray, len: Int = data.size): Boolean {
        if (!hasReader()) return false
        val f = fd ?: return false
        return try {
            Os.write(f, data, 0, len) == len
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EAGAIN) close()   // EPIPE: reader gone
            false
        }
    }

    fun close() {
        fd?.let { runCatching { Os.close(it) } }
        fd = null
    }
}
