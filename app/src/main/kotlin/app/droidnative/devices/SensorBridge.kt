package app.droidnative.devices

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToLong

/**
 * Phone sensors in the layout of the kernel's IIO sysfs interface, bound at
 * /sys/bus/iio/devices in the guest: one iio:deviceN directory per sensor
 * with the usual attribute files, rewritten in place as values change.
 */
class SensorBridge(private val context: Context, private val dir: File) {

    private class Channel(val type: Int, val name: String, val files: List<String>,
                          val static: Map<String, String>, val convert: (FloatArray) -> List<String>)

    private class Device(val channel: Channel, val dir: File) {
        val channels = channel.files.map { RandomAccessFile(File(dir, it), "rw").channel }
        var lastWrite = 0L
    }

    private val sm = context.getSystemService(SensorManager::class.java)
    private val devices = HashMap<Int, Device>()
    private var thread: HandlerThread? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val d = devices[e.sensor.type] ?: return
            val now = SystemClock.uptimeMillis()
            if (now - d.lastWrite < MIN_INTERVAL_MS) return
            d.lastWrite = now
            val values = d.channel.convert(e.values)
            for (i in values.indices) writeValue(d.channels[i], values[i])
        }
        override fun onAccuracyChanged(s: Sensor, accuracy: Int) {}
    }

    /** Creates the sysfs tree (also when the bridge does not run: binds need it). */
    fun prepare() {
        dir.mkdirs()
        var n = 0
        for (ch in CHANNELS) {
            val sensor = sm?.getDefaultSensor(ch.type) ?: continue
            val d = File(dir, "iio:device$n")
            d.mkdirs()
            File(d, "name").writeText(ch.name + "\n")
            File(d, "label").writeText(sensor.name + "\n")
            for ((file, value) in ch.static) File(d, file).writeText(value + "\n")
            for (file in ch.files) {
                val f = File(d, file)
                if (!f.exists()) f.writeText(pad("0"))
            }
            n++
        }
    }

    fun start() {
        if (thread != null || sm == null) return
        prepare()
        val t = HandlerThread("dn-sensors").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        var n = 0
        for (ch in CHANNELS) {
            val sensor = sm.getDefaultSensor(ch.type) ?: continue
            devices[ch.type] = Device(ch, File(dir, "iio:device$n"))
            n++
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, handler)
        }
    }

    fun stop() {
        sm?.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
        for (d in devices.values) d.channels.forEach { runCatching { it.close() } }
        devices.clear()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 50L
        private const val WIDTH = 15

        // Right-aligned, fixed width: a reader with an open descriptor never
        // sees a mix of an old long and a new short value
        private fun pad(v: String) = v.padStart(WIDTH) + "\n"

        private fun writeValue(ch: FileChannel, v: String) {
            runCatching { ch.write(ByteBuffer.wrap(pad(v).toByteArray()), 0) }
        }

        private fun raw(scale: Double) = { v: FloatArray ->
            (0..2).map { (v[it] / scale).roundToLong().toString() }
        }

        private val XYZ = listOf("x", "y", "z")
        private const val MATRIX = "1, 0, 0; 0, 1, 0; 0, 0, 1"

        // IIO units: accel m/s^2, anglvel rad/s, magn gauss, illuminance lux,
        // pressure kPa. Android: same, but magnetic field in uT, pressure hPa.
        private val CHANNELS = listOf(
            Channel(Sensor.TYPE_ACCELEROMETER, "accel_3d", XYZ.map { "in_accel_${it}_raw" },
                mapOf("in_accel_scale" to "0.001000", "in_accel_mount_matrix" to MATRIX), raw(0.001)),
            Channel(Sensor.TYPE_GYROSCOPE, "gyro_3d", XYZ.map { "in_anglvel_${it}_raw" },
                mapOf("in_anglvel_scale" to "0.001000", "in_anglvel_mount_matrix" to MATRIX), raw(0.001)),
            Channel(Sensor.TYPE_MAGNETIC_FIELD, "magn_3d", XYZ.map { "in_magn_${it}_raw" },
                mapOf("in_magn_scale" to "0.000100", "in_magn_mount_matrix" to MATRIX),
                { v -> (0..2).map { (v[it] / 100.0 / 0.0001).roundToLong().toString() } }),
            Channel(Sensor.TYPE_LIGHT, "als", listOf("in_illuminance_input"), emptyMap(),
                { v -> listOf("%.2f".format(java.util.Locale.ROOT, v[0])) }),
            Channel(Sensor.TYPE_PRESSURE, "pressure", listOf("in_pressure_input"), emptyMap(),
                { v -> listOf("%.4f".format(java.util.Locale.ROOT, v[0] / 10.0)) }),
            Channel(Sensor.TYPE_PROXIMITY, "proximity", listOf("in_proximity_raw"),
                mapOf("in_proximity_scale" to "1"), { v -> listOf(v[0].roundToLong().toString()) }),
        )
    }
}
