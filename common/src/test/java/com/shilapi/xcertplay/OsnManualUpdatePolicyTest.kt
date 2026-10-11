package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.res.Resources
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.BackgroundUpdateScheduler
import com.shilapi.xcertplay.update.UpdateAvailability
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OsnManualUpdatePolicyTest {
    @Test fun upstreamAutomaticChecksCannotRunForTheOsnProductEvenWithStoredOptIn() {
        val app = RuntimeEnvironment.getApplication()
        val resources = mock(Resources::class.java)
        `when`(resources.getBoolean(R.bool.config_osn_ui)).thenReturn(true)
        val osn = object : ContextWrapper(app) { override fun getResources(): Resources = resources }
        UpdateAvailability.saveBackgroundChecksEnabled(osn, true)
        assertFalse(UpdateAvailability.backgroundChecksEnabled(osn))
        // WorkManager is deliberately uninitialized: a scheduling attempt would fail here.
        BackgroundUpdateScheduler.schedule(osn)
    }
}
