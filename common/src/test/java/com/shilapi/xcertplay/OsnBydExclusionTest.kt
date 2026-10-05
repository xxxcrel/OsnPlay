package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.hud.BydOutputSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OsnBydExclusionTest {
    @Test fun productExclusionOverridesEvenPreviouslyEnabledVehicleSettings() {
        val app = RuntimeEnvironment.getApplication()
        BydOutputSettings.setBatteryToIphone(app, true)
        BydOutputSettings.setWheelSpeedToIphone(app, true)
        BydOutputSettings.setVideoWhileParked(app, true)
        BydOutputSettings.setHudSong(app, true)
        BydOutputSettings.setOemClusterHold(app, com.shilapi.xcertplay.hud.BydOemClusterHold.PACKAGE)
        val resources = mock(Resources::class.java)
        `when`(resources.getBoolean(com.shilapi.xcertplay.shared.R.bool.config_byd_features)).thenReturn(false)
        val excluded = object : ContextWrapper(app) { override fun getResources(): Resources = resources }
        assertFalse(BydOutputSettings.enabled(excluded))
        assertFalse(BydOutputSettings.batteryToIphoneActive(excluded))
        assertFalse(BydOutputSettings.wheelSpeedToIphoneActive(excluded))
        assertFalse(BydOutputSettings.videoWhileParkedActive(excluded))
        assertFalse(BydOutputSettings.navigationAvailable(excluded))
        assertFalse(BydOutputSettings.available(excluded))
        assertFalse(BydOutputSettings.hudSong(excluded))
        assertEquals(com.shilapi.xcertplay.hud.BydOemClusterHold.OFF, BydOutputSettings.oemClusterHold(excluded))
    }
}
