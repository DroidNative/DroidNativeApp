package app.droidnative

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.droidnative.apps.DesktopEntry
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.Shortcuts
import app.droidnative.display.Compositor
import app.droidnative.display.WindowActivity
import app.droidnative.env.EnvController
import app.droidnative.env.EnvService
import app.droidnative.ui.DroidNativeTheme
import java.io.File
import kotlinx.coroutines.delay

/**
 * Starts a Linux application and shows a loading screen until its first
 * window opens, then hands over to that window. Used by the Overview, the
 * Install tab and home screen shortcuts (droidnative://app/<id>); Android
 * lets the service open a window only while one of the app's activities
 * is visible. If the program exits with an error before a window appears,
 * the screen shows its log, which can be saved to a file. If it ends
 * abnormally later (error code, killed, crashed), a notification says why
 * and opens the same screen ([EXTRA_ENDED]) without starting it again.
 */
class LaunchActivity : ComponentActivity(), Compositor.Listener {

    /** Failure shown instead of the progress indicator. */
    private class Failure(val message: String, val log: String)

    private var entry: DesktopEntry? = null
    private var icon by mutableStateOf<Bitmap?>(null)
    private var failure by mutableStateOf<Failure?>(null)
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.data?.takeIf { it.scheme == Shortcuts.SCHEME && it.host == Shortcuts.HOST_APP }
            ?.lastPathSegment
        EnvController.refresh(this)
        val state = EnvController.status.value.state
        val apps = LinuxApps(this)
        val found = id?.let { apps.find(it) }
        if (found == null || state == EnvController.State.NOT_INSTALLED ||
            state == EnvController.State.INSTALLING) {
            // Uninstalled app, or the environment needs the main screen first
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        entry = found
        icon = apps.icon(found)
        setContent { DroidNativeTheme { Screen(found) } }
        // From the "stopped" notification: only the report
        intent.getStringExtra(EXTRA_ENDED)?.let {
            failure = Failure(it, readTail(apps.logFile(found)))
            return
        }
        EnvService.compositor.addListener(this)
        // Recreated (rotation): the launch is already running or failed
        if (savedInstanceState == null) Thread({ start(found) }, "dn-launch").start()
        else if (!EnvService.isRunning) finish()
    }

    override fun onDestroy() {
        EnvService.compositor.removeListener(this)
        super.onDestroy()
    }

    @Composable
    private fun Screen(e: DesktopEntry) {
        val f = failure
        val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { saveLog(e, it) }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp,
                    if (f == null) Alignment.CenterVertically else Alignment.Top),
                horizontalAlignment = Alignment.CenterHorizontally) {
                icon?.let { Image(it.asImageBitmap(), null, Modifier.size(if (f == null) 96.dp else 64.dp)) }
                Text(e.name, style = MaterialTheme.typography.headlineSmall)
                if (f == null) {
                    CircularProgressIndicator()
                    // Programs that run without a window keep the screen up: let it go
                    var slow by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { delay(SLOW_MS); slow = true }
                    if (slow) TextButton({ finish() }) { Text(stringResource(R.string.launch_hide)) }
                } else {
                    Text(f.message, color = MaterialTheme.colorScheme.error)
                    OutlinedCard(Modifier.fillMaxWidth().weight(1f)) {
                        Text(f.log.ifEmpty { stringResource(R.string.launch_log_empty) },
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState(Int.MAX_VALUE))
                                .horizontalScroll(rememberScrollState()).padding(12.dp),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ save.launch("${e.id.removeSuffix(".desktop")}-log.txt") }) {
                            Text(stringResource(R.string.launch_save_log))
                        }
                        Button({ finish() }) { Text(stringResource(R.string.launch_close)) }
                    }
                }
            }
        }
    }

    private fun start(e: DesktopEntry) {
        val apps = LinuxApps(this)
        val log = apps.logFile(e)
        if (!EnvService.isRunning) EnvController.start(this)
        // Clients need the Wayland socket, which appears with the service
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!EnvService.compositor.isRunning && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        if (!EnvService.compositor.isRunning) {
            fail(getString(R.string.launch_env_failed), null)
            return
        }
        val proc = try {
            apps.launch(e)
        } catch (ex: Exception) {
            Log.w(TAG, "launch ${e.id}", ex)
            fail(getString(R.string.apps_start_failed, e.name) + ": " + ex.message, null)
            return
        }
        val code = proc.waitFor()
        Log.i(TAG, "${e.id} exited with $code (window: $done)")
        if (done) {
            if (code != 0) reportEnded(applicationContext, e, code)
            return
        }
        if (code != 0) {
            fail(if (code > 128) endReason(this, e, code) else getString(R.string.launch_exited, e.name, code), log)
            return
        }
        // Exit 0 without a window: usually handed over to a running instance,
        // whose new window may still be on its way
        SystemClock.sleep(HANDOVER_MS)
        if (!done) runOnUiThread { if (!isFinishing) finish() }
    }

    private fun fail(message: String, log: File?) {
        val text = log?.let { readTail(it) } ?: ""
        runOnUiThread { failure = Failure(message, text) }
    }

    private fun saveLog(e: DesktopEntry, uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                LinuxApps(this).logFile(e).inputStream().use { it.copyTo(out) }
            }
            Toast.makeText(this, R.string.launch_log_saved, Toast.LENGTH_SHORT).show()
        } catch (ex: Exception) {
            Log.w(TAG, "save log", ex)
            Toast.makeText(this, getString(R.string.launch_log_save_failed, ex.message), Toast.LENGTH_LONG).show()
        }
    }

    // The first new window after the launch is taken as the application's
    override fun onToplevelNew(toplevel: Compositor.Toplevel) {
        if (done || failure != null) return
        done = true
        runOnUiThread {
            startActivity(WindowActivity.intent(this, toplevel.id))
            finish()
        }
    }

    companion object {
        private const val TAG = "dn-launch"
        private const val SLOW_MS = 15_000L
        private const val HANDOVER_MS = 5_000L
        private const val LOG_TAIL = 64 * 1024

        /** Message of the "stopped" notification, shown instead of starting. */
        const val EXTRA_ENDED = "app.droidnative.extra.ENDED"
        private const val ENDED_CHANNEL = "app_ended"
        private const val ENDED_NOTIFICATION_BASE = 0x3000
        // A swipe from recents may end the program on purpose (Compositor)
        private const val HOST_CLOSE_MS = 10_000L
        private const val STOP_SETTLE_MS = 2_000L

        /** Why a program ended; [code] as proot reports it (128 + signal when killed). */
        fun endReason(context: Context, e: DesktopEntry, code: Int): String = when (code - 128) {
            9 -> context.getString(R.string.app_ended_killed, e.name)
            11 -> context.getString(R.string.app_ended_segv, e.name)
            6 -> context.getString(R.string.app_ended_abort, e.name)
            5 -> context.getString(R.string.app_ended_trap, e.name)
            else -> if (code > 128) context.getString(R.string.app_ended_signal, e.name, code - 128)
                    else context.getString(R.string.app_ended_code, e.name, code)
        }

        /**
         * A program ended abnormally after its window was shown: tell the
         * user why instead of letting the window vanish. Ends the user caused
         * (closed from recents, environment stopped, SIGTERM) are not reported.
         */
        private fun reportEnded(context: Context, e: DesktopEntry, code: Int) {
            if (code == 128 + 15) return
            if (SystemClock.elapsedRealtime() - EnvService.compositor.lastHostClose < HOST_CLOSE_MS) return
            SystemClock.sleep(STOP_SETTLE_MS)
            if (!EnvService.isRunning) return
            val message = endReason(context, e, code)
            Log.w(TAG, message)
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(
                ENDED_CHANNEL, context.getString(R.string.channel_app_ended), NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(Intent.ACTION_VIEW, Shortcuts.uri(e), context, LaunchActivity::class.java)
                .putExtra(EXTRA_ENDED, message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val id = ENDED_NOTIFICATION_BASE + (e.id.hashCode() and 0xfff)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, ENDED_CHANNEL)
                          else @Suppress("DEPRECATION") Notification.Builder(context)
            nm.notify(id, builder
                .setSmallIcon(R.drawable.ic_penguin)
                .setContentTitle(context.getString(R.string.app_ended_title, e.name))
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message + "\n" +
                    context.getString(R.string.app_ended_tap)))
                .setContentIntent(PendingIntent.getActivity(context, id, intent, flags))
                // Own group: Android would bundle it with the service notice
                .setGroup(ENDED_CHANNEL)
                .setAutoCancel(true)
                .build())
        }

        /** Opens the loading screen, which starts [entry]. */
        fun start(context: Context, entry: DesktopEntry) {
            val intent = Intent(Intent.ACTION_VIEW, Shortcuts.uri(entry), context, LaunchActivity::class.java)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /** The end of a log, enough to see why a program failed. */
        private fun readTail(f: File): String = runCatching {
            f.inputStream().use { input ->
                val skip = f.length() - LOG_TAIL
                if (skip > 0) input.skip(skip)
                String(input.readBytes()).let { if (skip > 0) it.substringAfter('\n') else it }
            }
        }.getOrDefault("")
    }
}
