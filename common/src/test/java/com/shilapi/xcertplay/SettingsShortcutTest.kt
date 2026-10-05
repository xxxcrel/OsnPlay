package com.shilapi.xcertplay

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.host.R
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SettingsShortcutTest {
    private val down = listOf(AirPlayContact(0, .01, .5, true))
    private val packets = mutableListOf<List<AirPlayContact>>()
    private var opened = 0
    private val shortcut get() = EdgeHoldShortcut(Handler(Looper.getMainLooper()), 24f, 8f, { packets += it }, { opened++ })
    private fun event(action: Int, x: Float = 10f, y: Float = 500f) = MotionEvent.obtain(0, 0, action, x, y, 0)

    @Test fun oneFingerSideHoldOpensOnceWithoutSendingAPhoneLongPress() {
        val gesture = shortcut
        assertTrue(gesture.touch(event(MotionEvent.ACTION_DOWN), 1920, 1080, down))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
        assertEquals(0, opened); assertTrue(packets.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, opened); assertEquals(listOf(emptyList<AirPlayContact>()), packets)
        gesture.touch(event(MotionEvent.ACTION_UP), 1920, 1080, emptyList())
        assertEquals(1, opened)
    }
    @Test fun shortEdgeTapStillClicksCarPlayRatherThanOpeningSettings() {
        val gesture = shortcut
        gesture.touch(event(MotionEvent.ACTION_DOWN), 1920, 1080, down)
        assertTrue(gesture.touch(event(MotionEvent.ACTION_UP), 1920, 1080, emptyList()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, opened); assertEquals(listOf(down, emptyList()), packets)
    }
    @Test fun aDragCancelsTheHoldAndReplaysTheInitialContact() {
        val gesture = shortcut
        gesture.touch(event(MotionEvent.ACTION_DOWN), 1920, 1080, down)
        assertFalse(gesture.touch(event(MotionEvent.ACTION_MOVE, 35f), 1920, 1080, down))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, opened); assertEquals(listOf(down), packets)
    }
    @Test fun leavingTheWindowCancelsPendingCallbacksAndTheCenterIsNotReserved() {
        val gesture = shortcut
        assertFalse(gesture.touch(event(MotionEvent.ACTION_DOWN, 500f), 1920, 1080, down))
        gesture.touch(event(MotionEvent.ACTION_DOWN), 1920, 1080, down)
        gesture.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, opened); assertTrue(packets.isEmpty())
    }
    @Test fun buttonOnlyModeCannotHideItsOnlyEntryAndOpensTheRenamedActivity() {
        val app = RuntimeEnvironment.getApplication()
        SettingsShortcut.save(app, SettingsShortcut.Mode.BUTTON_ONLY)
        SettingsShortcut.button(app, false)
        assertTrue(SettingsShortcut.button(app))
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.callInstanceMethod<android.view.View>(host, "buildContentView")
        val button = ReflectionHelpers.getField<Button>(host, "shortcutButton")
        button.performClick()
        val intent = shadowOf(host).nextStartedActivity
        assertEquals(OsnPlayActivity::class.java.name, intent.component?.className)
        assertEquals("settings", intent.getStringExtra("page"))
    }
}
