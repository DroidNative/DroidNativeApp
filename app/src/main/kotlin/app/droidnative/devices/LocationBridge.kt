package app.droidnative.devices

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Phone location as a GPS receiver at /dev/gps0 in the guest: NMEA 0183
 * sentences (GGA, RMC) once per fix, like a serial GPS. Android location
 * updates run only while a guest program has the device open.
 */
class LocationBridge(private val context: Context, dir: File) {
    private val fifo = FifoWriter(File(dir, "gps0"))
    private val lm = context.getSystemService(LocationManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var updating = false

    private val listener = LocationListener { send(it) }

    fun prepare() = fifo.create()

    fun start() {
        if (thread != null || lm == null) return
        prepare()
        val t = HandlerThread("dn-location").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(poll) }
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        stopUpdates()
        thread?.quitSafely()
        thread = null
        handler = null
        fifo.close()
    }

    // A reader appears or goes away: start or stop Android's updates
    private val poll = object : Runnable {
        override fun run() {
            val reader = fifo.hasReader()
            if (reader && !updating) startUpdates()
            else if (!reader && updating) stopUpdates()
            handler?.postDelayed(this, POLL_MS)
        }
    }

    @SuppressLint("MissingPermission")   // checked through Passthrough before start()
    private fun startUpdates() {
        val m = lm ?: return
        val providers = m.getProviders(true)
        val provider = listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER)
            .firstOrNull { it in providers } ?: return
        try {
            m.requestLocationUpdates(provider, 1000L, 0f, listener, thread?.looper ?: Looper.getMainLooper())
            updating = true
            m.getLastKnownLocation(provider)?.let { send(it) }
        } catch (_: Exception) {
        }
    }

    private fun stopUpdates() {
        runCatching { lm?.removeUpdates(listener) }
        updating = false
    }

    private fun send(loc: Location) {
        val data = (gga(loc) + rmc(loc)).toByteArray(Charsets.US_ASCII)
        if (!fifo.write(data) && updating && !fifo.hasReader()) stopUpdates()
    }

    companion object {
        private const val POLL_MS = 2000L

        private fun utc(loc: Location): Calendar =
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = loc.time }

        private fun time(c: Calendar) = String.format(Locale.ROOT, "%02d%02d%02d.%02d",
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
            c.get(Calendar.MILLISECOND) / 10)

        // ddmm.mmmmm,N / dddmm.mmmmm,E
        private fun coord(value: Double, degDigits: Int, pos: Char, neg: Char): String {
            val a = abs(value)
            val deg = a.toInt()
            val min = (a - deg) * 60
            return String.format(Locale.ROOT, "%0${degDigits}d%08.5f,%c", deg, min, if (value >= 0) pos else neg)
        }

        private fun sentence(body: String): String {
            var sum = 0
            for (ch in body) sum = sum xor ch.code
            return String.format(Locale.ROOT, "$%s*%02X\r\n", body, sum)
        }

        fun gga(loc: Location): String {
            val hdop = if (loc.hasAccuracy()) loc.accuracy / 5.0 else 1.0
            val alt = if (loc.hasAltitude()) String.format(Locale.ROOT, "%.1f", loc.altitude) else ""
            val sats = loc.extras?.getInt("satellites", 0) ?: 0
            return sentence(String.format(Locale.ROOT, "GPGGA,%s,%s,%s,1,%02d,%.1f,%s,M,,M,,",
                time(utc(loc)), coord(loc.latitude, 2, 'N', 'S'), coord(loc.longitude, 3, 'E', 'W'),
                sats, hdop, alt))
        }

        fun rmc(loc: Location): String {
            val c = utc(loc)
            val knots = if (loc.hasSpeed()) String.format(Locale.ROOT, "%.2f", loc.speed * 1.943844) else ""
            val course = if (loc.hasBearing()) String.format(Locale.ROOT, "%.1f", loc.bearing) else ""
            val date = String.format(Locale.ROOT, "%02d%02d%02d", c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100)
            return sentence(String.format(Locale.ROOT, "GPRMC,%s,A,%s,%s,%s,%s,%s,,,A",
                time(c), coord(loc.latitude, 2, 'N', 'S'), coord(loc.longitude, 3, 'E', 'W'),
                knots, course, date))
        }
    }
}
