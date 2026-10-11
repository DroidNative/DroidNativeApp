package app.droidnative.test

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Full on-device suite, in dependency order. Run through tests/device/run.sh
 * (installs without uninstalling, so the rootfs survives between runs).
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    T01RootfsTest::class,
    T02ProotTest::class,
    T03GuestSetupTest::class,
    T04GraphicsTest::class,
    T05CompositorTest::class,
    T06VulkanWaylandTest::class,
    T07ZinkWaylandTest::class,
    T08AppImageTest::class,
    T09FlatpakTest::class,
    T10AudioTest::class,
    T11DevicesTest::class,
)
class DeviceSuite
