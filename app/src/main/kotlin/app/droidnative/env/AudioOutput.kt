package app.droidnative.env

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Plays what the guest's PulseAudio writes into its FIFO (module-pipe-sink,
 * s16le 48 kHz stereo, see assets/guest/pulse.pa) through an AudioTrack.
 * The blocking AudioTrack writes are what clocks the PulseAudio sink.
 *
 * The sink keeps the FIFO full, so the pipe's capacity is latency: the
 * default 64 KiB are 340 ms. The pipe is shrunk to [PIPE_BYTES] and the
 * AudioTrack buffer ([bufferMs], the "Audio buffer" setting) is the cushion
 * against a guest that delivers late.
 */
class AudioOutput(private val fifo: File, bufferMs: Int) {

    data class Stats(val bytesPlayed: Long, val peak: Int, val trackFrames: Long)

    private val bytesPlayed = AtomicLong()
    @Volatile private var peak = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var bufferMs = bufferMs
    private var thread: Thread? = null

    val stats get() = Stats(bytesPlayed.get(), peak,
        track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0)

    fun resetPeak() { peak = 0 }

    /** Changes the AudioTrack buffer while playing. */
    fun setBuffer(ms: Int) {
        bufferMs = ms
        track?.let { applyBuffer(it) }
    }

    private fun applyBuffer(t: AudioTrack) {
        t.bufferSizeInFrames = (RATE * bufferMs / 1000).coerceAtMost(t.bufferCapacityInFrames)
    }

    @Synchronized
    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({ loop() }, "dn-audio").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        // A busy guest (software rendering) must not starve the feeder: the
        // AudioTrack buffer only covers [bufferMs]
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            // Capacity for the largest setting; the used part is set below
            .setBufferSizeInBytes(maxOf(minBuf, RATE * FRAME_BYTES * MAX_BUFFER_MS / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        applyBuffer(t)
        track = t
        val buf = ByteArray(RATE * FRAME_BYTES / 50)   // 20 ms
        try {
            while (running) {
                // PulseAudio creates the FIFO when its sink loads
                if (!fifo.exists()) { Thread.sleep(250); continue }
                try {
                    FileInputStream(fifo).use { input ->
                        var shrunk = shrinkPipe(input.fd)
                        t.play()
                        var underruns = t.underrunCount
                        var checked = 0L
                        while (running) {
                            val n = input.read(buf)
                            if (n < 0) break            // writer closed: reopen
                            if (n == 0) continue
                            // Still full from before: drop it once instead of
                            // playing it 340 ms late forever
                            if (!shrunk) { shrunk = shrinkPipe(input.fd); continue }
                            trackPeak(buf, n)
                            t.write(buf, 0, n)
                            bytesPlayed.addAndGet(n.toLong())
                            val now = System.nanoTime()
                            if (now - checked > 2_000_000_000L) {
                                checked = now
                                val u = t.underrunCount
                                if (u != underruns) Log.w(TAG, "underruns: ${u - underruns} (total $u)")
                                underruns = u
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "audio fifo: ${e.message}")
                    Thread.sleep(500)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            track = null
            runCatching { t.stop() }
            t.release()
        }
    }

    // True when done or impossible; false while the pipe holds more than fits
    private fun shrinkPipe(fd: FileDescriptor): Boolean = try {
        Os.fcntlInt(fd, F_SETPIPE_SZ, PIPE_BYTES)
        true
    } catch (e: ErrnoException) {
        if (e.errno != OsConstants.EBUSY) Log.w(TAG, "pipe size: ${e.message}")
        e.errno != OsConstants.EBUSY
    }

    // Loudest sample seen since resetPeak(); for diagnostics and tests
    private fun trackPeak(buf: ByteArray, n: Int) {
        var p = peak
        var i = 0
        while (i + 1 < n) {
            val s = abs((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8))
            if (s > p) p = s
            i += 2 * 16   // every 16th sample is plenty
        }
        peak = p
    }

    companion object {
        private const val TAG = "dn-audio"
        const val RATE = 48000
        const val FRAME_BYTES = 4   // s16 stereo
        const val MAX_BUFFER_MS = 400
        // Two pages: the sink writes 4 KiB blocks (PIPE_BUF), 43 ms
        private const val PIPE_BYTES = 8192
        private const val F_SETPIPE_SZ = 1031
    }
}
