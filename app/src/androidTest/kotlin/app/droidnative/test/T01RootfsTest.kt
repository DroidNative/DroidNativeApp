package app.droidnative.test

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T01RootfsTest {

    @Test
    fun deviceInfo() {
        TestEnv.report("device", "model=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
            "abis=${Build.SUPPORTED_ABIS.joinToString(",")} arch=${TestEnv.arch.voidName} " +
            "kernel=${System.getProperty("os.version")}")
        for (node in listOf("/dev/kgsl-3d0", "/dev/dma_heap/system", "/dev/ion", "/dev/dri"))
            TestEnv.report("device", "$node exists=${File(node).exists()}")
    }

    @Test
    fun hostExecutablesPresent() {
        val p = TestEnv.paths
        for (f in listOf(p.proot, p.prootLoader)) {
            assertTrue("$f missing", f.isFile)
            assertTrue("$f not executable", f.canExecute())
        }
    }

    @Test
    fun installRootfs() {
        val t0 = System.currentTimeMillis()
        TestEnv.ensureRootfs()
        TestEnv.report("rootfs", "ready in ${(System.currentTimeMillis() - t0) / 1000}s")
        assertTrue(File(TestEnv.paths.rootfs, "usr/bin/xbps-install").isFile)
        assertTrue(File(TestEnv.paths.rootfs, "etc/resolv.conf").isFile)
    }
}
