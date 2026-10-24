package app.droidnative.bridge

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.droidnative.env.EnvPaths
import app.droidnative.env.EnvService
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject

/**
 * App side of the guest bridge: serves `/run/droidnative/bridge.sock` for
 * dn-notifyd (droidnative-tools), which relays desktop notifications,
 * MPRIS media players and text fields of X11 programs. One JSON object per line in both directions, see
 * docs/bridge-protocol.md. Started and stopped by EnvService.
 */
object Bridge {
    private const val TAG = "dn-bridge"

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var socketFile: File? = null
    private var server: LocalServerSocket? = null
    @Volatile private var running = false
    @Volatile private var client: LocalSocket? = null
    private var notifications: LinuxNotifications? = null
    private var players: MediaPlayers? = null

    @Synchronized
    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        val file = File(EnvPaths(app).runDir, "bridge.sock")
        file.parentFile?.mkdirs()
        file.delete()
        val ls = LocalSocket(LocalSocket.SOCKET_STREAM)
        try {
            ls.bind(LocalSocketAddress(file.path, LocalSocketAddress.Namespace.FILESYSTEM))
        } catch (e: IOException) {
            Log.w(TAG, "cannot bind ${file.path}", e)
            return
        }
        server = LocalServerSocket(ls.fileDescriptor)
        socketFile = file
        notifications = LinuxNotifications(app)
        players = MediaPlayers(app)
        running = true
        Thread({ acceptLoop() }, "dn-bridge-accept").start()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        val file = socketFile
        // accept() is not interrupted by close(): wake it with a connection
        runCatching {
            LocalSocket().connect(LocalSocketAddress(file?.path, LocalSocketAddress.Namespace.FILESYSTEM))
        }
        runCatching { server?.close() }
        runCatching { client?.close() }
        file?.delete()
        val n = notifications
        val p = players
        main.post { n?.clear(); p?.clear() }
    }

    /** Sends [message] to dn-notifyd; dropped when it is not connected. */
    fun send(message: JSONObject) {
        val c = client ?: return
        writer.execute {
            try {
                val out: OutputStream = c.outputStream
                out.write((message.toString() + "\n").toByteArray())
                out.flush()
            } catch (e: IOException) {
                Log.w(TAG, "send: ${e.message}")
            }
        }
    }

    fun send(type: String, vararg fields: Pair<String, Any>) =
        send(JSONObject().put("type", type).apply { for ((k, v) in fields) put(k, v) })

    private fun acceptLoop() {
        while (running) {
            val c = try { server?.accept() ?: break } catch (e: IOException) { break }
            if (!running) { c.close(); break }
            // One dn-notifyd at a time; a restarted one replaces the old
            client?.let { old -> runCatching { old.close() } }
            client = c
            Thread({ serve(c) }, "dn-bridge-client").start()
        }
    }

    private fun serve(c: LocalSocket) {
        try {
            c.inputStream.bufferedReader().forEachLine { line ->
                val m = try { JSONObject(line) } catch (e: JSONException) {
                    Log.w(TAG, "bad message: ${line.take(200)}")
                    return@forEachLine
                }
                main.post { dispatch(m) }
            }
        } catch (e: IOException) {
            Log.i(TAG, "client gone: ${e.message}")
        }
        if (client === c) {
            client = null
            // Its notifications and players can no longer be answered
            val n = notifications
            val p = players
            main.post { n?.clear(); p?.clear() }
        }
        runCatching { c.close() }
    }

    private fun dispatch(m: JSONObject) {
        when (m.optString("type")) {
            "hello" -> Log.i(TAG, "dn-notifyd connected, protocol ${m.optInt("version")}")
            "notify" -> notifications?.post(m)
            "close" -> notifications?.cancel(m.optInt("id"))
            "player" -> players?.update(m)
            "player-gone" -> players?.remove(m.optString("name"))
            "im-focus" -> EnvService.compositor.inputMethodFocus(m.optBoolean("active"))
            else -> Log.w(TAG, "unknown message ${m.optString("type")}")
        }
    }
}
