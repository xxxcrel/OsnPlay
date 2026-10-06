package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

class OsnUpdateUiTestActivity : OsnPlayActivity() {
    override val modernUi get() = true
    override val updatesEnabled get() = true
    override fun updateManager(context: Context) = fixture.manager
    override fun uiDeviceSource(context: Context): () -> OsnUiDeviceSnapshot = { OsnUiDeviceSnapshot(bluetoothEnabled = true) }
    override fun launchUpdateInstaller(file: File) { installations += file }
    companion object {
        internal lateinit var fixture: OsnUpdateTestFixture
        val installations = mutableListOf<File>()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "zh-rCN-w1280dp-h720dp-land-hdpi",
    shadows = [OsnUpdateUiTest.InstallPermission::class])
class OsnUpdateUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<OsnUpdateUiTestActivity>>()
    private val fixture get() = OsnUpdateUiTestActivity.fixture
    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("osnplay", 0).edit().clear().commit()
        val directory = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        OsnUpdateUiTestActivity.fixture = OsnUpdateTestFixture(directory).also { it.publish() }
        OsnUpdateUiTestActivity.installations.clear(); InstallPermission.granted = false
    }
    @After fun cleanup() {
        controllers.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        CarPlayBackgroundSession.clear()
    }
    @Test fun checkAndDownloadUpdateOnlyTheirControlsAndInstallRevalidatesTheFile() {
        val activity = create().get()
        val root = activity.window.decorView
        tagged<Button>(activity, "update-check").performClick()
        assertFalse(tagged<Button>(activity, "update-check").isEnabled)
        fixture.worker.drain()
        assertEquals(OsnUpdatePhase.AVAILABLE, fixture.manager.state.phase)
        assertTrue(tagged<TextView>(activity, "update-status").text.contains("1.5.1"))
        assertSame(root, activity.window.decorView)
        tagged<Button>(activity, "update-action").performClick(); fixture.worker.drain()
        assertEquals(activity.getString(R.string.osn_update_install), tagged<Button>(activity, "update-action").text.toString())
        assertTrue(OsnUpdateUiTestActivity.installations.isEmpty())
        InstallPermission.granted = true
        tagged<Button>(activity, "update-action").performClick(); fixture.worker.drain()
        assertEquals(1, OsnUpdateUiTestActivity.installations.size)
        assertEquals(2, fixture.validationCount)
    }
    @Test fun missingInstallationPermissionOpensOnlyTheSystemSourceSettings() {
        val activity = create().get()
        fixture.manager.check(); fixture.worker.drain(); fixture.manager.download(); fixture.worker.drain()
        tagged<Button>(activity, "update-action").performClick()
        val started = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.action)
        assertEquals("package:${activity.packageName}", started.data.toString())
        assertTrue(OsnUpdateUiTestActivity.installations.isEmpty())
    }
    @Test fun anUpdateSurvivesWindowDestructionAndTheNextWindowShowsTheReadyApk() {
        val old = create()
        fixture.manager.check(); fixture.worker.drain(); fixture.manager.download()
        old.pause().stop().destroy(); fixture.worker.drain()
        val next = create().get()
        assertEquals(OsnUpdatePhase.READY, fixture.manager.state.phase)
        assertEquals(next.getString(R.string.osn_update_install), tagged<Button>(next, "update-action").text.toString())
        assertTrue(OsnUpdateUiTestActivity.installations.isEmpty())
    }
    @Test fun startupResumeAndOpeningTheUpdatePageDoNotContactGitHub() {
        context.getSharedPreferences("osnplay_updates", 0).edit().putBoolean("automatic", true).commit()
        val controller = create()
        fixture.worker.drain()
        assertEquals(OsnUpdatePhase.IDLE, fixture.manager.state.phase)
        assertTrue(fixture.http.requests.isEmpty())
        controller.pause().resume()
        fixture.worker.drain()
        assertTrue(fixture.http.requests.isEmpty())
        tagged<Button>(controller.get(), "update-check").performClick()
        controller.pause().stop().destroy()
        fixture.worker.drain()
        assertEquals(OsnUpdatePhase.AVAILABLE, fixture.manager.state.phase)
        assertEquals(2, fixture.http.requests.size)
        assertTrue(OsnUpdateUiTestActivity.installations.isEmpty())
    }

    private fun create(): ActivityController<OsnUpdateUiTestActivity> {
        val controller = Robolectric.buildActivity(OsnUpdateUiTestActivity::class.java,
            Intent(context, OsnUpdateUiTestActivity::class.java).putExtra("page", "settings")).setup()
        controllers += controller
        ReflectionHelpers.setField(controller.get(), "settingsCategory", "updates")
        ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "render")
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }
    private inline fun <reified T : View> tagged(activity: OsnPlayActivity, tag: String): T =
        descendants(activity.window.decorView).first { it.tag == tag } as T
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    @Implements(OsnUpdateInstaller::class, isInAndroidSdk = false)
    class InstallPermission {
        @Implementation fun permitted(context: Context): Boolean = granted
        companion object { var granted = false }
    }
}
