package app.droidnative.devices

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Phone microphone as the PulseAudio source "android_mic" in the guest
 * (module-pipe-source on a FIFO, loaded by session.sh). session.sh writes
 * 1 or 0 to mic.active as recording streams come and go; the microphone is
 * only open while something records.
 */
class MicBridge(runDir: File) {
    private val fifo = FifoWriter(File(runDir, "mic.fifo"))
    private val active = File(runDir, "mic.active")
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "dn-mic").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun wanted() = runCatching { active.readText().trim() == "1" }.getOrDefault(false)

    @SuppressLint("MissingPermission")   // checked through Passthrough before start()
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) {
                if (!wanted()) { Thread.sleep(300); continue }
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5))
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "microphone unavailable")
                    rec.release()
                    Thread.sleep(2000)
                    continue
                }
                Log.i(TAG, "recording")
                rec.startRecording()
                val buf = ByteArray(RATE * 2 / 50)   // 20 ms
                var checked = 0L
                try {
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) fifo.write(buf, n)
                        val now = System.nanoTime()
                        if (now - checked > 300_000_000L) {
                            checked = now
                            if (!wanted()) break
                        }
                    }
                } finally {
                    rec.stop()
                    rec.release()
                    fifo.close()
                    Log.i(TAG, "stopped")
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "dn-mic"
        const val RATE = 48000
    }
}
