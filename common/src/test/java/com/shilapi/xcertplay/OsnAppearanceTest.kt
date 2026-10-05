package com.shilapi.xcertplay

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OsnAppearanceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { context.getSharedPreferences("osnplay_appearance", 0).edit().clear().commit() }

    @Test fun freshInstallAndInvalidPreferenceFollowSystemWithoutChangingOtherUiModeBits() {
        assertEquals(OsnAppearance.Mode.SYSTEM, OsnAppearance.mode(context))
        val darkCar = Configuration(context.resources.configuration).apply {
            uiMode = Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_YES
        }
        assertTrue(OsnAppearance.dark(context, darkCar))
        darkCar.uiMode = Configuration.UI_MODE_TYPE_CAR or Configuration.UI_MODE_NIGHT_NO
        assertFalse(OsnAppearance.dark(context, darkCar))
        context.getSharedPreferences("osnplay_appearance", 0).edit().putString("theme", "invalid").commit()
        assertEquals(OsnAppearance.Mode.SYSTEM, OsnAppearance.mode(context))
    }

    @Test fun manualOverrideSurvivesSystemChangesAndCanReturnToAutomatic() {
        val day = Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
        val night = Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        OsnAppearance.save(context, OsnAppearance.Mode.DARK)
        assertTrue(OsnAppearance.dark(context, day))
        OsnAppearance.save(context, OsnAppearance.Mode.LIGHT)
        assertFalse(OsnAppearance.dark(context, night))
        OsnAppearance.save(context, OsnAppearance.Mode.SYSTEM)
        assertTrue(OsnAppearance.dark(context, night))
        assertFalse(OsnAppearance.dark(context, day))
    }

    @Test fun explicitPaletteUsesItsOwnNightResourcesRatherThanTheSystemQualifier() {
        RuntimeEnvironment.setQualifiers("+night")
        OsnAppearance.save(context, OsnAppearance.Mode.LIGHT)
        val light = OsnAppearance.palette(context)
        assertFalse(light.dark)
        assertEquals(0xFFF3F6F3.toInt(), light.background)
        OsnAppearance.save(context, OsnAppearance.Mode.DARK)
        val dark = OsnAppearance.palette(context)
        assertTrue(dark.dark)
        assertEquals(0xFF111914.toInt(), dark.background)
        assertNotEquals(light.text, dark.text)
    }

    @Test fun twelveInchDefaultAndManualInterfaceSizePersistWithSafeFallback() {
        assertEquals(125, OsnAppearance.size(context))
        OsnAppearance.saveSize(context, 140)
        assertEquals(140, OsnAppearance.size(context))
        context.getSharedPreferences("osnplay_appearance", 0).edit().putInt("ui_size", 2000).commit()
        assertEquals(125, OsnAppearance.size(context))
        context.getSharedPreferences("osnplay_appearance", 0).edit().putString("ui_size", "broken").commit()
        assertEquals(125, OsnAppearance.size(context))
    }
}
