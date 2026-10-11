package app.droidnative.env

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Plays what the guest's PulseAudio writes into its FIFO (module-pipe-sink,
 * s16le 48 kHz stereo, see assets/guest/pulse.pa) through an AudioTrack.
 * The blocking AudioTrack writes are what clocks the PulseAudio sink.
 */
class AudioOutput(private val fifo: File) {

    data class Stats(val bytesPlayed: Long, val peak: Int, val trackFrames: Long)

    private val bytesPlayed = AtomicLong()
    @Volatile private var peak = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    val stats get() = Stats(bytesPlayed.get(), peak,
        track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0)

    fun resetPeak() { peak = 0 }

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
        // AudioTrack buffer only covers about 100 ms
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
            .setBufferSizeInBytes(maxOf(minBuf, RATE * FRAME_BYTES / 10))   // >= 100 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        val buf = ByteArray(RATE * FRAME_BYTES / 50)   // 20 ms
        try {
            while (running) {
                // PulseAudio creates the FIFO when its sink loads
                if (!fifo.exists()) { Thread.sleep(250); continue }
                try {
                    FileInputStream(fifo).use { input ->
                        t.play()
                        var underruns = t.underrunCount
                        var checked = 0L
                        while (running) {
                            val n = input.read(buf)
                            if (n < 0) break            // writer closed: reopen
                            if (n == 0) continue
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
    }
}
