package com.shilapi.xcertplay

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

class OsnPlayUiTestActivity : OsnPlayActivity() {
    override val modernUi get() = true
    override fun requiresManualHotspotInterface() = true
    override fun uiDeviceSource(context: android.content.Context): () -> OsnUiDeviceSnapshot {
        val app = context.applicationContext
        return {
            val permissions = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION).filter { app.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }.toSet()
            val interfaces = listOf("wlan1", "eth0.3").mapIndexed { index, name ->
                com.shilapi.xcertplay.network.ManualHotspotInterface(name, index + 1,
                    listOf(java.net.InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 43, 1))))
            }
            OsnUiDeviceSnapshot(bluetoothEnabled = true, hotspotEnabled = true, grantedPermissions = permissions, interfaces = interfaces)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1280dp-h720dp-land-hdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OsnPlayUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<OsnPlayUiTestActivity>>()

    @Before fun reset() {
        for (name in listOf("osnplay_appearance", "osnplay", "xcertplay_airplay")) context.getSharedPreferences(name, 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
    }

    @After fun cleanup() {
        controllers.forEach { it.pause().stop().destroy() }
        CarPlayBackgroundSession.clear()
    }

    @Test fun missingDeviceAndHotspotAreNotPresentedAsConfigured() {
        val activity = create("home").get()
        assertTrue(texts(activity).contains(activity.getString(R.string.osn_device_missing)))
        assertTrue(texts(activity).any { it.contains(activity.getString(R.string.osn_hotspot_missing)) })
        assertFalse(texts(activity).any { it == activity.getString(R.string.osn_device_saved) })
        tagged(activity, "quick-0").performClick()
        assertEquals("connection", ReflectionHelpers.getField<String>(activity, "page"))
    }

    @Test fun liveSystemThemeKeepsSettingsCategoryScrollAndTheActiveConnection() {
        val activity = create("settings").get()
        tagged(activity, "category-audio").performClick()
        layout(activity)
        val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
        scroll.scrollTo(0, 80)
        val position = scroll.scrollY
        var stopped = false
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", { _: () -> Unit -> stopped = true })
        CarPlayBackgroundSession.active = true
        RuntimeEnvironment.setQualifiers("+night")
        activity.onConfigurationChanged(Configuration(activity.resources.configuration))
        layout(activity)
        assertTrue(OsnAppearance.palette(activity).dark)
        assertEquals("audio", ReflectionHelpers.getField<String>(activity, "settingsCategory"))
        assertEquals(position, ReflectionHelpers.getField<ScrollView>(activity, "rootScroll").scrollY)
        assertEquals(OsnAppearance.palette(activity).background, (tagged(activity, "osn_ui_root").background as ColorDrawable).color)
        assertFalse(stopped)
        assertTrue(CarPlayBackgroundSession.active)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun manualThemeAndCategorySurviveActivityRecreation() {
        val controller = create("settings")
        val activity = controller.get()
        tagged(activity, "theme-dark").performClick()
        tagged(activity, "category-display").performClick()
        controller.recreate()
        val recreated = controller.get()
        assertEquals(OsnAppearance.Mode.DARK, OsnAppearance.mode(recreated))
        assertEquals("display", ReflectionHelpers.getField<String>(recreated, "settingsCategory"))
        assertTrue(tagged(recreated, "category-display").isSelected)
    }

    @Test fun followingSystemAlsoWorksWithAnExplicitAppLanguageAndAMissedUiModeCallback() {
        AppLocale.save(context, AppLocale.SIMPLIFIED_CHINESE)
        val activity = create("home").get()
        RuntimeEnvironment.setQualifiers("+night")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
        assertTrue(OsnAppearance.palette(activity).dark)
        assertEquals(OsnAppearance.palette(activity).background, (tagged(activity, "osn_ui_root").background as ColorDrawable).color)
    }

    @Test fun narrowWindowKeepsUsbAndSettingsCategoriesReachable() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w600dp-h400dp-land-hdpi")
        val activity = create("connection").get()
        tagged(activity, "transport-usb").performClick()
        tagged(activity, "nav-settings").performClick()
        tagged(activity, "category-audio").performClick()
        assertEquals("audio", ReflectionHelpers.getField<String>(activity, "settingsCategory"))
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "connectionWireless"))
    }

    @Test fun themeChangeKeepsAnUnsavedHotspotDialogAndDoesNotCommitTheDraft() {
        AirPlayPersistence.saveManualHotspotSsid(context, "Saved hotspot")
        val activity = create("connection").get()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "editModernHotspot")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val fields = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        fields[0].setText("Unsaved name")
        fields[1].setText("unsaved-password")
        RuntimeEnvironment.setQualifiers("+night")
        activity.onConfigurationChanged(Configuration(activity.resources.configuration))
        assertTrue(dialog.isShowing)
        assertEquals("Unsaved name", fields[0].text.toString())
        assertEquals("unsaved-password", fields[1].text.toString())
        assertEquals(OsnAppearance.palette(activity).text, fields[0].currentTextColor)
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals("Saved hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
    }

    @Test fun categorizedSettingsKeepAudioAndLocationControlsAccessible() {
        val activity = create("settings").get()
        assertFalse(descendants(activity.window.decorView).filterIsInstance<Switch>().any {
            it.contentDescription == activity.getString(R.string.osn_microphone_compatibility)
        })
        tagged(activity, "category-audio").performClick()
        val microphone = descendants(activity.window.decorView).filterIsInstance<Switch>().single {
            it.contentDescription == activity.getString(R.string.osn_microphone_compatibility)
        }
        microphone.performClick()
        assertTrue(AirPlayPersistence.loadStandardMicrophoneInput(context))
        tagged(activity, "category-connection").performClick()
        assertTrue(descendants(activity.window.decorView).filterIsInstance<Switch>().any {
            it.contentDescription == activity.getString(R.string.report_location_to_iphone)
        })
    }

    @Test fun increasingInterfaceSizeEnlargesControlsWithoutRestartingCarPlay() {
        val activity = create("settings").get()
        val before = tagged(activity, "nav-home").layoutParams.height
        var stopped = false
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", { _: () -> Unit -> stopped = true })
        CarPlayBackgroundSession.active = true
        val control = descendants(activity.window.decorView).first {
            it.contentDescription?.toString()?.startsWith(activity.getString(R.string.osn_ui_size) + " ·") == true
        }
        control.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 3, 3)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(140, OsnAppearance.size(activity))
        assertTrue(tagged(activity, "nav-home").layoutParams.height > before)
        assertFalse(stopped)
        assertTrue(CarPlayBackgroundSession.active)
    }

    @Test fun usbStartsTheExistingHostWithoutClearingSavedWirelessSettings() {
        AirPlayPersistence.saveManualHotspotInterface(context, "eth0.3")
        val activity = create("connection").get()
        tagged(activity, "transport-usb").performClick()
        tagged(activity, "connection-connect").performClick()
        assertFalse(AirPlayPersistence.loadWirelessEnabled(context))
        assertEquals("eth0.3", AirPlayPersistence.loadManualHotspotInterface(context))
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun wirelessPermissionSummaryReflectsHostRequirementsButUsbDoesNotRequireWirelessPermissions() {
        shadowOf(context).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val activity = create("connection").get()
        assertTrue(texts(activity).contains(activity.getString(R.string.osn_permissions_missing)))
        tagged(activity, "transport-usb").performClick()
        assertTrue(texts(activity).contains(activity.getString(R.string.osn_permissions_ready)))
    }

    @Test fun cachedModernPickerStillRequiresARealChoiceAndDoesNotInsertAnOldInterface() {
        AirPlayPersistence.saveManualHotspotInterface(context, "en0.2")
        val activity = create("connection").get()
        val control = descendants(activity.window.decorView).first {
            it.contentDescription?.toString()?.startsWith(activity.getString(R.string.osn_network_interface) + " ·") == true
        }
        control.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(2, dialog.listView.adapter.count)
        assertEquals(-1, dialog.listView.checkedItemPosition)
        assertFalse(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.listView.performItemClick(null, 1, 1)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("eth0.3", AirPlayPersistence.loadManualHotspotInterface(activity))
    }

    @Test fun nativeLayoutsSupportBothPalettesAndCanRenderReviewScreenshots() {
        OsnPlayPreferences.savePhone(context, "00:11:22:33:44:55", "我的 iPhone")
        AirPlayPersistence.saveManualHotspotSsid(context, "OSN-Car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "sample-password")
        AirPlayPersistence.saveManualHotspotInterface(context, "eth0.3")
        val directory = System.getenv("OSNPLAY_UI_SCREENSHOTS")?.let(::File)
        for (mode in listOf(OsnAppearance.Mode.LIGHT, OsnAppearance.Mode.DARK)) {
            OsnAppearance.save(context, mode)
            for (page in listOf("home", "connection", "settings")) {
                val activity = create(page).get()
                layout(activity)
                val root = tagged(activity, "osn_ui_root")
                assertEquals(1920, root.width)
                assertEquals(1080, root.height)
                assertTrue(tagged(activity, "nav-$page").isSelected)
                assertTrue(descendants(root).filterIsInstance<Button>().all { it.visibility == View.GONE || it.measuredHeight >= 72 })
                if (page == "home") assertTrue(tagged(activity, "quick-0").measuredHeight >= 100)
                if (page == "connection") {
                    val action = tagged(activity, "connection-connect")
                    val position = IntArray(2)
                    action.getLocationOnScreen(position)
                    assertTrue(position[1] + action.height <= root.height)
                }
                if (directory != null) {
                    check(directory.isDirectory)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(directory, "$page-${mode.name.lowercase()}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun sameLanSetupUsesItsOwnCredentialsWithoutAHotspotInterface() {
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(context, "Pocket", "pocket-password")
        val activity = create("connection").get()
        assertTrue(texts(activity).contains(activity.getString(R.string.existing_wifi_title)))
        assertTrue(texts(activity).contains(activity.getString(R.string.existing_wifi_details)))
        assertFalse(texts(activity).contains(activity.getString(R.string.osn_network_interface)))
        assertNull(AirPlayPersistence.loadManualHotspotInterface(activity))
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(activity, "hotspotConfigured"))
        val edit = descendants(activity.window.decorView).filterIsInstance<Button>()
            .first { it.text.toString() == activity.getString(R.string.existing_wifi_details) }
        edit.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val fields = descendants(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().toList()
        assertEquals("Pocket", fields[0].text.toString())
        assertEquals("pocket-password", fields[1].text.toString())
        fields[0].setText("Other LAN")
        fields[1].setText("other-password")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Other LAN", AirPlayPersistence.loadExistingWifiSsid(context))
        assertTrue(AirPlayPersistence.loadManualHotspotSsid(context).isNullOrEmpty())
    }

    private fun create(page: String): ActivityController<OsnPlayUiTestActivity> {
        val controller = Robolectric.buildActivity(OsnPlayUiTestActivity::class.java,
            Intent(context, OsnPlayUiTestActivity::class.java).putExtra("page", page)).setup()
        controllers += controller
        val monitor = ReflectionHelpers.getField<OsnUiDeviceMonitor>(controller.get(), "deviceMonitor")
        val deadline = System.nanoTime() + 2_000_000_000L
        while (monitor.latest == null && System.nanoTime() < deadline) Thread.sleep(1)
        assertNotNull(monitor.latest)
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setField(controller.get(), "setupError", null)
        ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "render")
        return controller
    }

    private fun layout(activity: OsnPlayUiTestActivity) {
        val root = tagged(activity, "osn_ui_root")
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun texts(activity: OsnPlayUiTestActivity) = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun tagged(activity: OsnPlayUiTestActivity, tag: String) = descendants(activity.window.decorView).first { it.tag == tag }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
