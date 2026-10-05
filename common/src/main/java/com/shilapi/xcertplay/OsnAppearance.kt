package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.shilapi.xcertplay.host.R

/** OsnPlay's UI preference is independent of the phone's CarPlay appearance. */
internal object OsnAppearance {
    enum class Mode(val label: Int) {
        SYSTEM(R.string.osn_theme_system), LIGHT(R.string.osn_theme_light), DARK(R.string.osn_theme_dark),
    }

    data class Palette(
        val dark: Boolean, val background: Int, val surface: Int, val soft: Int,
        val border: Int, val accent: Int, val onAccent: Int, val text: Int,
        val muted: Int, val tint: Int, val warning: Int,
    )

    private const val PREFS = "osnplay_appearance"
    val sizes = listOf(100, 115, 125, 140)
    private fun defaultSize(context: Context): Int = if (context.resources.displayMetrics.densityDpi <= 160 && context.resources.displayMetrics.widthPixels >= 1800) 140 else 125
    fun size(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("ui_size", defaultSize(context))
    }.getOrDefault(defaultSize(context)).takeIf { it in sizes } ?: defaultSize(context)

    fun saveSize(context: Context, percent: Int) {
        require(percent in sizes)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("ui_size", percent).apply()
    }

    fun mode(context: Context): Mode = runCatching {
        Mode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", "SYSTEM")!!)
    }.getOrDefault(Mode.SYSTEM)

    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", mode.name).apply()
    }

    // A locale-wrapped activity may retain its creation-time night qualifier.
    // The application resources remain the system source of truth.
    fun dark(context: Context, configuration: Configuration = context.applicationContext.resources.configuration): Boolean = when (mode(context)) {
        Mode.LIGHT -> false
        Mode.DARK -> true
        Mode.SYSTEM -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(context: Context): Palette {
        val dark = dark(context)
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val resources = context.createConfigurationContext(configuration).resources
        fun color(id: Int) = resources.getColor(id, null)
        return Palette(dark, color(R.color.osn_background), color(R.color.osn_surface), color(R.color.osn_soft),
            color(R.color.osn_border), color(R.color.osn_accent), color(R.color.osn_on_accent), color(R.color.osn_text),
            color(R.color.osn_muted), color(R.color.osn_tint), color(R.color.osn_warning))
    }

    fun style(context: Context) = if (dark(context)) R.style.Theme_OsnPlay_Dark else R.style.Theme_OsnPlay_Light

    val legacy = Palette(true, Color.rgb(12, 17, 27), Color.rgb(21, 30, 44), Color.rgb(21, 30, 44),
        Color.rgb(42, 56, 75), Color.rgb(166, 200, 255), Color.rgb(12, 17, 27), Color.rgb(241, 245, 252),
        Color.rgb(168, 182, 202), Color.rgb(42, 56, 75), Color.rgb(255, 196, 128))
}
