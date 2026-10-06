package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

class OsnDialogThemeTestActivity : OsnPlayActivity() {
    override val modernUi get() = true
    override fun uiDeviceSource(context: Context): () -> OsnUiDeviceSnapshot = { OsnUiDeviceSnapshot(bluetoothEnabled = true) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1280dp-h720dp-land-hdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OsnDialogThemeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val windows = mutableListOf<ActivityController<OsnDialogThemeTestActivity>>()
    @Before fun reset() {
        app.getSharedPreferences("osnplay_appearance", 0).edit().clear().commit()
        app.getSharedPreferences("osnplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }
    @After fun cleanup() { windows.forEach { it.pause().stop().destroy() } }

    @Test fun numericDialogsKeepReadableTextHintsAndButtonsInBothPalettes() {
        for (mode in listOf(OsnAppearance.Mode.LIGHT, OsnAppearance.Mode.DARK)) {
            OsnAppearance.save(app, mode)
            val activity = create()
            for (kind in listOf("resolution", "delay", "threshold")) {
                val dialog = open(activity, kind)
                assertPalette(dialog, OsnAppearance.palette(activity))
                val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().first()
                input.setText("invalid")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing); assertNotNull(input.error)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                assertNull(input.error)
                assertPalette(dialog, OsnAppearance.palette(activity))
                screenshot(dialog, "$kind-${mode.name.lowercase()}")
                dialog.dismiss()
            }
        }
    }
    @Test fun anOpenResolutionDialogChangesPaletteWithoutLosingItsDraftOrCompoundingTextSize() {
        OsnAppearance.save(app, OsnAppearance.Mode.LIGHT)
        val activity = create()
        val dialog = open(activity, "resolution")
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().first()
        input.setText("74")
        val textSize = input.textSize
        for (mode in listOf(OsnAppearance.Mode.DARK, OsnAppearance.Mode.LIGHT)) {
            OsnAppearance.save(app, mode)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            assertPalette(dialog, OsnAppearance.palette(activity))
            assertEquals("74", input.text.toString()); assertEquals(textSize, input.textSize, .01f)
        }
        dialog.dismiss()
    }
    private fun create(): OsnDialogThemeTestActivity {
        val controller = Robolectric.buildActivity(OsnDialogThemeTestActivity::class.java).setup()
        windows += controller
        return controller.get()
    }
    private fun open(activity: OsnDialogThemeTestActivity, kind: String): AlertDialog {
        val parent = LinearLayout(activity)
        val type = OsnPlayActivity::class.java
        if (kind == "threshold") {
            type.getDeclaredMethod("ambientLightThresholdControl", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, parent)
        } else {
            val name = if (kind == "resolution") "resolutionSettingControl" else "nightDelaySettingControl"
            val method = type.declaredMethods.first { it.name == name }.apply { isAccessible = true }
            method.invoke(activity, parent,
                if (kind == "resolution") R.string.resolution else R.string.ambient_delay_title,
                if (kind == "resolution") R.string.custom_resolution_hint else R.string.ambient_delay_hint,
                if (kind == "resolution") 30..100 else 0..60, if (kind == "resolution") 100 else 2,
                if (kind == "resolution") R.string.custom_resolution_summary else R.string.ambient_delay_summary,
                { if (kind == "resolution") 80 else 2 }, false, { _: Int -> })
        }
        parent.getChildAt(0).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }
    private fun assertPalette(dialog: AlertDialog, palette: OsnAppearance.Palette) {
        val background = (dialog.window!!.decorView.background as GradientDrawable).color!!.defaultColor
        assertEquals(palette.surface, background)
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().first()
        assertEquals(palette.text, input.currentTextColor)
        assertEquals(palette.muted, input.currentHintTextColor)
        assertTrue(ColorUtils.calculateContrast(input.currentTextColor, background) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(input.currentHintTextColor, background) >= 4.5)
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(which)
            assertEquals(palette.accent, button.currentTextColor)
            assertTrue(ColorUtils.calculateContrast(button.currentTextColor, background) >= 3)
        }
    }
    private fun screenshot(dialog: AlertDialog, name: String) {
        val directory = System.getenv("OSNPLAY_DIALOG_SCREENSHOTS")?.let(::File) ?: return
        check(directory.isDirectory)
        val view = dialog.window!!.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
