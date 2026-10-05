package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.host.R

internal object SettingsShortcut {
    enum class Mode(val label: Int) {
        EDGE_HOLD(R.string.osn_shortcut_edge), BUTTON_ONLY(R.string.osn_shortcut_button), MULTI_SWIPE(R.string.osn_shortcut_multi),
    }
    fun mode(context: Context): Mode = runCatching {
        Mode.valueOf(context.getSharedPreferences("osnplay", 0).getString("settings_shortcut", context.getString(R.string.config_default_settings_shortcut))!!)
    }.getOrDefault(Mode.EDGE_HOLD)
    fun save(context: Context, mode: Mode) { context.getSharedPreferences("osnplay", 0).edit().putString("settings_shortcut", mode.name).apply() }
    fun button(context: Context) = mode(context) == Mode.BUTTON_ONLY || context.getSharedPreferences("osnplay", 0).getBoolean("settings_button", context.resources.getBoolean(R.bool.config_settings_button_default))
    fun button(context: Context, shown: Boolean) { context.getSharedPreferences("osnplay", 0).edit().putBoolean("settings_button", shown).apply() }
}

/** Reserve only a narrow middle-side strip. Short taps/drags are replayed to CarPlay. */
internal class EdgeHoldShortcut(
    private val handler: Handler,
    private val edgeWidth: Float,
    private val touchSlop: Float,
    private val send: (List<AirPlayContact>) -> Unit,
    private val open: () -> Unit,
) {
    private var pending = false
    private var opened = false
    private var downX = 0f
    private var downY = 0f
    private var initial: List<AirPlayContact> = emptyList()
    private val hold = Runnable {
        if (pending) {
            pending = false; opened = true; initial = emptyList()
            send(emptyList()); open()
        }
    }

    fun touch(event: MotionEvent, width: Int, height: Int, contacts: List<AirPlayContact>): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            if (event.pointerCount == 1 && (event.x <= edgeWidth || event.x >= width - edgeWidth) && event.y in height * .25f..height * .75f) {
                pending = true; downX = event.x; downY = event.y; initial = contacts.toList()
                handler.postDelayed(hold, 1000)
                return true
            }
            return false
        }
        if (opened) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancel()
            return true
        }
        if (!pending) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> { val tap = initial; cancel(); if (tap.isNotEmpty()) send(tap); send(emptyList()); return true }
            MotionEvent.ACTION_CANCEL -> { cancel(); return true }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1 || kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop) {
                    val start = initial; cancel(); if (start.isNotEmpty()) send(start)
                    return false
                }
            }
        }
        return true
    }

    fun cancel() { handler.removeCallbacks(hold); pending = false; opened = false; initial = emptyList() }
}
