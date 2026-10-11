package app.droidnative.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.net.Uri
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.droidnative.R
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.WindowSettings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * MPRIS media players of the guest as Android media sessions with a media
 * notification: now playing (title, artist, album, cover), play/pause,
 * previous/next and a seek bar. Buttons and the seek bar go back to the
 * player through dn-notifyd. Main thread.
 */
class MediaPlayers(private val context: Context) {
    private class Player(val name: String, val session: MediaSession, val notificationId: Int) {
        var trackId = ""
        var art: Bitmap? = null
        var artUrl = ""
        var last: JSONObject? = null
        var icon: AppIcon? = null
        var iconFor: String? = null
    }

    private val nm = context.getSystemService(NotificationManager::class.java)
    private val apps = LinuxApps(context)
    private val players = HashMap<String, Player>()
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 0

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.channel_linux_media), NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun update(m: JSONObject) {
        val name = m.optString("name")
        val p = players[name] ?: create(name)
        p.last = m
        p.trackId = m.optString("trackid")
        val status = m.optString("status")
        if (status == "Stopped" && m.optString("title").isEmpty()) {
            // Nothing loaded: no controls, like an idle Android player
            p.session.isActive = false
            nm.cancel(p.notificationId)
            return
        }
        loadArt(p, m.optString("art"))
        p.session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, m.optString("title").ifEmpty { m.optString("identity") })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, m.optString("artist"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, m.optString("album"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, m.optLong("length", -1).let { if (it > 0) it / 1000 else -1 })
            .apply { p.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
            .build())

        val playing = status == "Playing"
        var actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (m.optBoolean("canPlay")) actions = actions or PlaybackState.ACTION_PLAY
        if (m.optBoolean("canPause")) actions = actions or PlaybackState.ACTION_PAUSE
        if (m.optBoolean("canNext")) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (m.optBoolean("canPrevious")) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (m.optBoolean("canSeek") && m.optLong("length", -1) > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        val position = m.optLong("position", -1).let { if (it >= 0) it / 1000 else PlaybackState.PLAYBACK_POSITION_UNKNOWN }
        p.session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(when (status) {
                "Playing" -> PlaybackState.STATE_PLAYING
                "Paused" -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_STOPPED
            }, position, if (playing) m.optDouble("rate", 1.0).toFloat() else 0f, SystemClock.elapsedRealtime())
            .build())
        p.session.isActive = true
        notify(p, m, playing)
    }

    fun remove(name: String) {
        val p = players.remove(name) ?: return
        nm.cancel(p.notificationId)
        p.session.release()
    }

    fun clear() {
        for (name in players.keys.toList()) remove(name)
    }

    private fun create(name: String): Player {
        val session = MediaSession(context, "dn-$name")
        val p = Player(name, session, BASE + nextId++)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = command(name, "play")
            override fun onPause() = command(name, "pause")
            override fun onStop() = command(name, "stop")
            override fun onSkipToNext() = command(name, "next")
            override fun onSkipToPrevious() = command(name, "previous")
            override fun onSeekTo(pos: Long) {
                Bridge.send("seek", "name" to name, "trackid" to p.trackId, "position" to pos * 1000)
            }
        }, main)
        players[name] = p
        return p
    }

    private fun notify(p: Player, m: JSONObject, playing: Boolean) {
        val pid = m.optInt("pid")
        val desktop = m.optString("desktop").ifEmpty { WindowSettings.resolveApp(context, pid) ?: "" }
        // The player's icon, looked up once per name it reports
        val key = "$desktop|${m.optString("identity")}"
        if (p.iconFor != key) {
            p.iconFor = key
            p.icon = AppIcon.of((apps.findLoose(desktop) ?: apps.findLoose(m.optString("identity")))
                ?.let { apps.icon(it) })
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
                else @Suppress("DEPRECATION") Notification.Builder(context)
        p.icon?.applyTo(b) ?: b.setSmallIcon(R.drawable.ic_penguin)
        b.setContentTitle(m.optString("title").ifEmpty { m.optString("identity") })
            .setContentText(m.optString("artist"))
            .setSubText(m.optString("identity").ifEmpty { null })
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOngoing(playing)
            .setContentIntent(NotificationOpenActivity.pendingIntent(context, -1 - p.notificationId,
                false, true, pid, desktop))
        (p.art ?: p.icon?.bitmap?.let { LinuxNotifications.scaled(it, 256) })?.let { b.setLargeIcon(it) }
        val compact = ArrayList<Int>()
        fun button(icon: Int, label: Int, cmd: String) {
            compact += compact.size
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(icon, context.getString(label),
                NotificationReceiver.media(context, p.name, cmd)).build())
        }
        if (m.optBoolean("canPrevious")) button(R.drawable.ic_skip_previous, R.string.media_previous, "previous")
        if (playing) button(R.drawable.ic_pause, R.string.media_pause, "pause")
        else button(R.drawable.ic_play, R.string.media_play, "play")
        if (m.optBoolean("canNext")) button(R.drawable.ic_skip_next, R.string.media_next, "next")
        b.setStyle(Notification.MediaStyle()
            .setMediaSession(p.session.sessionToken)
            .setShowActionsInCompactView(*compact.toIntArray()))
        nm.notify(p.notificationId, b.build())
    }

    // Cover from a file in the guest or from the web; the update that
    // follows its loading carries it
    private fun loadArt(p: Player, url: String) {
        if (url == p.artUrl) return
        p.artUrl = url
        p.art = null
        if (url.isEmpty()) return
        loader.execute {
            val bitmap = try {
                when {
                    url.startsWith("file://") -> apps.guestFile(Uri.decode(url.removePrefix("file://")))
                        ?.let { LinuxNotifications.decode(it) }
                    url.startsWith("http://") || url.startsWith("https://") -> download(url)
                    else -> null
                }
            } catch (e: Exception) {
                Log.w(TAG, "cover $url: ${e.message}")
                null
            }
            if (bitmap != null) main.post {
                if (players[p.name] === p && p.artUrl == url) {
                    p.art = LinuxNotifications.scaled(bitmap, ART_SIZE)
                    p.last?.let { update(it) }
                }
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "dn-media"
        private const val CHANNEL = "linux_media"
        private const val BASE = 200_000
        private const val ART_SIZE = 512

        fun command(name: String, cmd: String) = Bridge.send("media", "name" to name, "cmd" to cmd)
    }
}
