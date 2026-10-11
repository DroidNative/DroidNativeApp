package app.droidnative

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.droidnative.apps.DesktopEntry
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.Shortcuts
import app.droidnative.display.Compositor
import app.droidnative.display.WindowActivity
import app.droidnative.env.EnvController
import app.droidnative.env.EnvService
import app.droidnative.ui.DroidNativeTheme

/**
 * Target of home screen shortcuts (droidnative://app/<id>): starts the
 * environment if needed, launches the application and stays in front until
 * its window exists. Android lets the service open that window only while
 * one of the app's activities is visible.
 */
class LaunchActivity : ComponentActivity(), Compositor.Listener {

    private var entry: DesktopEntry? = null
    private var icon by mutableStateOf<Bitmap?>(null)
    private var failed by mutableStateOf(false)
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
        setContent {
            DroidNativeTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        icon?.let { Image(it.asImageBitmap(), null, Modifier.size(96.dp)) }
                        Text(found.name, style = MaterialTheme.typography.headlineSmall)
                        if (failed) {
                            Text(stringResource(R.string.apps_start_failed, found.name),
                                color = MaterialTheme.colorScheme.error)
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
        EnvService.compositor.addListener(this)
        Thread({ start(found) }, "dn-launch").start()
    }

    override fun onDestroy() {
        EnvService.compositor.removeListener(this)
        super.onDestroy()
    }

    private fun start(e: DesktopEntry) {
        if (!EnvService.isRunning) EnvController.start(this)
        // Clients need the Wayland socket, which appears with the service
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!EnvService.compositor.isRunning && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        try {
            LinuxApps(this).launch(e)
        } catch (ex: Exception) {
            Log.w(TAG, "launch ${e.id}", ex)
            runOnUiThread { failed = true }
            return
        }
        // Single-instance apps may only open a window in an existing process
        // much later, or never (already open): give up waiting after a while
        SystemClock.sleep(TIMEOUT_MS)
        if (!done) runOnUiThread { if (!isFinishing) finish() }
    }

    // The first new window after the launch is taken as the application's
    override fun onToplevelNew(toplevel: Compositor.Toplevel) {
        if (done) return
        done = true
        runOnUiThread {
            startActivity(WindowActivity.intent(this, toplevel.id))
            finish()
        }
    }

    companion object {
        private const val TAG = "dn-launch"
        private const val TIMEOUT_MS = 30_000L
    }
}
