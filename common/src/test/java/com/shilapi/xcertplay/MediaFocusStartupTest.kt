package com.shilapi.xcertplay

import android.media.AudioManager
import android.os.Looper
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaFocusStartupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val controller = mock(CarPlayController::class.java)
    private val audio get() = shadowOf(context.getSystemService(AudioManager::class.java))

    @Before fun resetPreferences() {
        context.getSharedPreferences("xcertplay_airplay", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun compatibilityModeHasNoSystemTransportSessionAndDoesNotRetakeFocusMidTrack() {
        AirPlayPersistence.saveSystemMediaSyncEnabled(context, false)
        CarPlayMediaKeys.attach(context, controller, claimFocusEarly = true)
        val request = audio.lastAudioFocusRequest
        assertNotNull(request)
        val field = CarPlayMediaKeys::class.java.getDeclaredField("session").apply { isAccessible = true }
        assertNull(field.get(CarPlayMediaKeys))
        request.listener!!.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(request, audio.lastAudioFocusRequest)
        assertNull(field.get(CarPlayMediaKeys))
    }

    @After fun cleanup() {
        shadowOf(Looper.getMainLooper()).idle()
        CarPlayMediaKeys.detach(controller)
    }

    @Test fun osnClaimsFocusBeforeMusicAndDoesNotRequestItAgainOnFirstPacket() {
        CarPlayMediaKeys.attach(context, controller, claimFocusEarly = true)
        val beforePacket = audio.lastAudioFocusRequest
        assertNotNull(beforePacket)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, beforePacket.audioFocusRequest.focusGain)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(beforePacket, audio.lastAudioFocusRequest)
        verify(controller).reportMediaDiagnostic(contains("phase=before-connection"))
    }

    @Test fun disabledEarlyFocusLeavesFactorySourceAloneUntilCarPlayMediaActuallyArrives() {
        AirPlayPersistence.saveEarlyMediaFocus(context, false)
        AirPlayPersistence.saveSystemMediaSyncEnabled(context, false)
        CarPlayMediaKeys.attach(context, controller)
        assertNull(audio.lastAudioFocusRequest)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(audio.lastAudioFocusRequest)
        verify(controller).reportMediaDiagnostic(contains("phase=first-audio"))
    }

    @Test fun reattachingSameControllerDoesNotReplaceTheFocusOwner() {
        CarPlayMediaKeys.attach(context, controller, claimFocusEarly = true)
        val request = audio.lastAudioFocusRequest
        CarPlayMediaKeys.attach(context, controller, claimFocusEarly = true)
        assertSame(request, audio.lastAudioFocusRequest)
    }

    @Test fun disconnectAbandonsTheEarlyFocusRequest() {
        CarPlayMediaKeys.attach(context, controller, claimFocusEarly = true)
        val request = audio.lastAudioFocusRequest
        CarPlayMediaKeys.detach(controller)
        assertSame(request.audioFocusRequest, audio.lastAbandonedAudioFocusRequest)
    }
}
