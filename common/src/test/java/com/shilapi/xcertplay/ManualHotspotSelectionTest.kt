package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.ManualHotspotInterface
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.net.InetAddress
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
class ManualHotspotSelectionTest {
    class SelectionActivity : OsnPlayActivity() {
        var networks = listOf(
            ManualHotspotInterface("wlan1", 7, listOf(InetAddress.getByName("192.168.43.1"))),
            ManualHotspotInterface("eth0.2", 8, listOf(InetAddress.getByName("192.168.50.1"))),
        )
        override fun requiresManualHotspotInterface() = true
        override fun availableManualHotspotInterfaces() = networks
    }

    private lateinit var activity: SelectionActivity

    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .edit().clear().commit()
        activity = Robolectric.buildActivity(SelectionActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @Test fun firstUseRequiresARealSelectionAndDoesNotPreselectTheFirstInterface() {
        val control = interfaceControl()
        assertTrue(control.text.contains(activity.getString(R.string.hotspot_network_pick_interface)))
        control.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(-1, dialog.listView.checkedItemPosition)
        assertEquals(2, dialog.listView.adapter.count)
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertNull(AirPlayPersistence.loadManualHotspotInterface(activity))
    }

    @Test fun savedChoiceIsRestoredAfterRecreatingTheActivity() {
        interfaceControl().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 1)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("eth0.2", AirPlayPersistence.loadManualHotspotInterface(activity))
        activity = Robolectric.buildActivity(SelectionActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        interfaceControl().performClick()
        assertEquals(1, ShadowAlertDialog.getLatestAlertDialog().listView.checkedItemPosition)
    }

    @Test fun unavailableSavedInterfaceIsNotInsertedAsAGhostChoice() {
        AirPlayPersistence.saveManualHotspotInterface(activity, "en0.2")
        val control = interfaceControl()
        assertFalse(control.text.contains("en0.2"))
        control.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(-1, dialog.listView.checkedItemPosition)
        assertEquals(2, dialog.listView.adapter.count)
        for (index in 0 until dialog.listView.adapter.count) {
            assertFalse(dialog.listView.adapter.getItem(index).toString().contains("en0.2"))
        }
    }

    @Test fun cancellingAPreviewKeepsTheSavedChoice() {
        AirPlayPersistence.saveManualHotspotInterface(activity, "wlan1")
        interfaceControl().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 1)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("wlan1", AirPlayPersistence.loadManualHotspotInterface(activity))
    }

    @Test fun emptyListDoesNotOfferAutomaticOrASyntheticInterface() {
        activity.networks = emptyList()
        interfaceControl().performClick()
        assertNull(AirPlayPersistence.loadManualHotspotInterface(activity))
        assertEquals(0, ManualHotspotSelection.choices(emptyList(), required = true).size)
    }

    @Test fun connectionRejectsMissingOrUnavailableChoiceButIgnoresItForUsbAndP2p() {
        fun error(name: String?, wireless: Boolean = true, mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL) =
            ManualHotspotSelection.error(true, wireless, mode, name, activity.networks)
        assertEquals(ManualHotspotSelection.Error.MISSING, error(null))
        assertEquals(ManualHotspotSelection.Error.UNAVAILABLE, error("en0.2"))
        assertNull(error("wlan1"))
        assertNull(error(null, wireless = false))
        assertNull(error(null, mode = WirelessHotspotMode.WIFI_P2P))
    }

    private fun interfaceControl(): Button {
        val parent = LinearLayout(activity)
        OsnPlayActivity::class.java.getDeclaredMethod("manualHotspotNetworkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        return (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<Button>()
            .first { it.text.startsWith(activity.getString(R.string.hotspot_network_interface_summary, "")) }
    }
}
