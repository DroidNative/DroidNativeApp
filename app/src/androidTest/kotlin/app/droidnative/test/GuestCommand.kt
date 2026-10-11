package app.droidnative.test

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug entry point, not part of the suite: runs one shell command in the
 * guest from inside the real app process (app SELinux domain and seccomp
 * filter, unlike adb run-as). Used by tests/device/app-sh.sh:
 *   -e cmd64 <base64 of the command> [-e timeout <seconds>] [-e devices 1]
 * "devices" starts the passthrough bridges (Devices) around the command.
 */
@RunWith(AndroidJUnit4::class)
class GuestCommand {
    @Test
    fun run() {
        val args = InstrumentationRegistry.getArguments()
        val cmd64 = args.getString("cmd64")
        assumeTrue("no cmd64 argument", cmd64 != null)
        val cmd = String(Base64.decode(cmd64, Base64.DEFAULT))
        val timeout = (args.getString("timeout")?.toLongOrNull() ?: 300) * 1000
        TestEnv.ensureRootfs()
        val devices = args.getString("devices") == "1"
        if (devices) app.droidnative.devices.Devices.start(TestEnv.context)
        val r = try {
            TestEnv.proot.sh(cmd, timeoutMs = timeout)
        } finally {
            if (devices) app.droidnative.devices.Devices.stop()
        }
        TestEnv.saveOutput("guest-command", r)
        val out = Bundle()
        out.putString("stream", "\n--- exit=${r.exitCode} timedOut=${r.timedOut}\n${r.output}--- end\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, out)
    }
}
