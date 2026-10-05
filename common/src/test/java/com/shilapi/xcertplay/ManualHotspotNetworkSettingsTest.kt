package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.ManualHotspotAddressMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", manifest = Config.NONE)
class ManualHotspotNetworkSettingsTest {
    private lateinit var activity: OsnPlayActivity
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().commit()
        activity = Robolectric.buildActivity(OsnPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @Test fun cancellingAddressSelectionDoesNotChangeNextConnection() {
        addressControl().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 1)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ManualHotspotAddressMode.AUTO, AirPlayPersistence.loadManualHotspotAddressMode(activity))
    }

    @Test fun ipv4SelectionIsSavedAndRestoredByTheDialog() {
        addressControl().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ManualHotspotAddressMode.IPV4, AirPlayPersistence.loadManualHotspotAddressMode(activity))
        addressControl().performClick()
        assertEquals(1, ShadowAlertDialog.getLatestAlertDialog().listView.checkedItemPosition)
    }

    @Test fun savedInterfaceAndAddressModeReachTheHostRuntimeConfig() {
        AirPlayPersistence.saveManualHotspotInterface(activity, "eth0.3")
        AirPlayPersistence.saveManualHotspotAddressMode(activity, ManualHotspotAddressMode.IPV4)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(host))
        val config = CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
        assertEquals("eth0.3", config.manualHotspotInterface)
        assertEquals(ManualHotspotAddressMode.IPV4, config.manualHotspotAddressMode)
    }

    @Test fun interfaceOverrideCanBeResetAndInvalidSavedNamesAreIgnored() {
        AirPlayPersistence.saveManualHotspotInterface(activity, "wlan1")
        AirPlayPersistence.saveManualHotspotInterface(activity, null)
        assertNull(AirPlayPersistence.loadManualHotspotInterface(activity))
        prefs.edit().putString("manual_hotspot_interface", "../../wlan0").commit()
        assertNull(AirPlayPersistence.loadManualHotspotInterface(activity))
        prefs.edit().putString("manual_hotspot_address_mode", "corrupt").commit()
        assertEquals(ManualHotspotAddressMode.AUTO, AirPlayPersistence.loadManualHotspotAddressMode(activity))
    }

    private fun addressControl(): Button {
        val parent = LinearLayout(activity)
        OsnPlayActivity::class.java.getDeclaredMethod("manualHotspotNetworkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        return (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<Button>()
            .first { it.text.startsWith(activity.getString(R.string.hotspot_network_address_summary, "")) }
    }
}
