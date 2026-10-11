package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.res.Resources
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.mockito.Mockito.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ClusterTurnCardPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
        AirPlayPersistence.overlaySettingsListener = null
    }

    @Test fun overlayDefaultsToRightOfCentre() {
        assertEquals(ClusterTurnCardOverlay.DEFAULT_X_PERCENT, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
        assertEquals(ClusterTurnCardOverlay.DEFAULT_Y_PERCENT, AirPlayPersistence.loadClusterTurnCardOverlayYPercent(context))
        assertEquals(55, AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(context))
    }

    @Test fun withdrawnInstrumentFeatureIgnoresAPreviouslyEnabledMapAfterUpgrade() {
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(context))
        val resources = mock(Resources::class.java)
        `when`(resources.getBoolean(R.bool.config_cluster_map_available)).thenReturn(false)
        val upgradedProduct = object : ContextWrapper(context) {
            override fun getResources(): Resources = resources
        }
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(upgradedProduct))
    }

    @Test fun overlayOffsetsRoundTrip() {
        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 90)
        AirPlayPersistence.saveClusterTurnCardOverlayYPercent(context, 16)
        AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(context, 35)
        assertEquals(90, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
        assertEquals(16, AirPlayPersistence.loadClusterTurnCardOverlayYPercent(context))
        assertEquals(35, AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(context))
    }

    @Test fun legacyMarkerStepsMigrateAroundTheSlidersCentre() {
        assertEquals(50, AirPlayPersistence.loadClusterMarkerXPercent(context))
        assertEquals(45, AirPlayPersistence.loadClusterMarkerYPercent(context))
        AirPlayPersistence.saveClusterMarkerHorizontalStep(context, 1)
        AirPlayPersistence.saveClusterMarkerVerticalStep(context, -2)
        assertEquals(60, AirPlayPersistence.loadClusterMarkerXPercent(context))
        assertEquals(25, AirPlayPersistence.loadClusterMarkerYPercent(context))
        AirPlayPersistence.saveClusterMarkerXPercent(context, 37)
        assertEquals(37, AirPlayPersistence.loadClusterMarkerXPercent(context))
    }

    @Test fun smallWindowCardDefaultsRightOfCentreUntilTheFullScreenCardWasMoved() {
        assertEquals(80, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(25, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(40, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))

        // A full-screen card the driver placed keeps applying in the small window, as before.
        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 60)
        AirPlayPersistence.saveClusterTurnCardOverlayYPercent(context, 40)
        AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(context, 70)
        assertEquals(60, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(40, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(70, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))

        AirPlayPersistence.saveClusterSmallWindowCardXPercent(context, 85)
        AirPlayPersistence.saveClusterSmallWindowCardYPercent(context, 20)
        AirPlayPersistence.saveClusterSmallWindowCardSizePercent(context, 35)
        assertEquals(85, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(20, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(35, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))
    }

    @Test fun legacyLeftCentreRightMigrateToPercents() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().putString("cluster_turn_card_overlay_position", "LEFT").apply()
        assertEquals(20, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
        prefs.edit().putString("cluster_turn_card_overlay_position", "CENTER").apply()
        assertEquals(50, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
        prefs.edit().putString("cluster_turn_card_overlay_position", "RIGHT").apply()
        assertEquals(76, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
    }

    @Test fun overlaySaveNotifiesTheHostWithoutReconnecting() {
        var noticed = 0
        AirPlayPersistence.overlaySettingsListener = { noticed++ }
        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 20)
        AirPlayPersistence.saveClusterTurnCardOverlayYPercent(context, 50)
        AirPlayPersistence.saveClusterTurnCardOverlaySizePercent(context, 90)
        assertEquals(3, noticed)
    }
}
