package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

class SlowStatusActivity : OsnPlayActivity() {
    override val modernUi get() = true
    override fun uiDeviceSource(context: Context): () -> OsnUiDeviceSnapshot = {
        check(Looper.myLooper() != Looper.getMainLooper())
        calls.incrementAndGet(); entered.countDown()
        check(release.await(10, TimeUnit.SECONDS))
        OsnUiDeviceSnapshot(bluetoothEnabled = true, hotspotEnabled = true)
    }
    companion object {
        var entered = CountDownLatch(1)
        var release = CountDownLatch(1)
        val calls = AtomicInteger()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1280dp-h720dp-land-hdpi", manifest = Config.NONE)
class OsnUiPerformanceTest {
    private lateinit var controller: ActivityController<SlowStatusActivity>
    private val activity get() = controller.get()

    @Before fun startBlockedProbe() {
        SlowStatusActivity.entered = CountDownLatch(1); SlowStatusActivity.release = CountDownLatch(1)
        SlowStatusActivity.calls.set(0)
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("osnplay", "xcertplay_airplay", "osnplay_appearance")) app.getSharedPreferences(name, 0).edit().clear().commit()
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        controller = Robolectric.buildActivity(SlowStatusActivity::class.java,
            Intent(app, SlowStatusActivity::class.java)).setup()
        assertTrue(SlowStatusActivity.entered.await(2, TimeUnit.SECONDS))
        assertNull(ReflectionHelpers.getField<OsnUiDeviceMonitor>(activity, "deviceMonitor").latest)
    }

    @After fun cleanup() {
        controller.pause().stop().destroy()
        SlowStatusActivity.release.countDown()
        CarPlayBackgroundSession.clear()
    }

    @Test fun blockedVendorServicesDoNotBlockTabsSaveOrCauseMoreStatusRequests() {
        val originalPalette = ReflectionHelpers.getField<OsnAppearance.Palette>(activity, "palette")
        repeat(8) {
            tagged("nav-settings").performClick()
            tagged("category-audio").performClick()
            tagged("nav-connection").performClick()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
        }
        assertSame(originalPalette, ReflectionHelpers.getField<OsnAppearance.Palette>(activity, "palette"))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "editModernHotspot")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idle()
        val fields = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        fields[0].setText("Updated hotspot"); fields[1].setText("test-password")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Updated hotspot", AirPlayPersistence.loadManualHotspotSsid(activity))
        assertFalse(dialog.isShowing)
        assertEquals(1, SlowStatusActivity.calls.get())
        assertEquals(1L, SlowStatusActivity.release.count)
    }

    @Test fun receivingAStatusSnapshotUpdatesLabelsWithoutRebuildingTheWindow() {
        tagged("nav-connection").performClick()
        val root = tagged("osn_ui_root")
        val renders = ReflectionHelpers.getField<Int>(activity, "uiRenderCount")
        SlowStatusActivity.release.countDown()
        val monitor = ReflectionHelpers.getField<OsnUiDeviceMonitor>(activity, "deviceMonitor")
        val deadline = System.nanoTime() + 2_000_000_000L
        while (monitor.latest == null && System.nanoTime() < deadline) Thread.sleep(1)
        assertNotNull(monitor.latest)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(root, tagged("osn_ui_root"))
        assertEquals(renders, ReflectionHelpers.getField<Int>(activity, "uiRenderCount"))
    }

    private fun tagged(tag: String) = descendants(activity.window.decorView).first { it.tag == tag }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
