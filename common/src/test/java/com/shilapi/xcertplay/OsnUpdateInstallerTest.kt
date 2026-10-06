package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.XmlResourceParser
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [FileProviderPathTestShadow::class])
class OsnUpdateInstallerTest {
    @Test fun installationUsesOnlyTheUpdaterCacheWithAReadOnlyContentUriGrant() {
        val app = RuntimeEnvironment.getApplication()
        val pm = mock(PackageManager::class.java)
        val context = object : ContextWrapper(app) { override fun getPackageManager(): PackageManager = pm }
        val authority = "${context.packageName}.updates"
        val providerInfo = mock(ProviderInfo::class.java).apply { this.authority = authority; exported = false; grantUriPermissions = true }
        `when`(providerInfo.loadXmlMetaData(pm, "android.support.FILE_PROVIDER_PATHS"))
            .thenAnswer { context.resources.getXml(R.xml.osn_update_paths) }
        `when`(pm.resolveContentProvider(authority, PackageManager.GET_META_DATA)).thenReturn(providerInfo)
        val provider = OsnUpdateProvider().also { it.attachInfo(context, providerInfo) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val file = File(File(context.cacheDir, "updates"), "12-${"a".repeat(64)}.apk").apply { parentFile!!.mkdirs(); writeText("verified APK") }
        val intent = OsnUpdateInstaller.installIntent(context, file)
        assertEquals("content", intent.data!!.scheme); assertEquals(authority, intent.data!!.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals("verified APK", context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use { it.readText() })
        val other = File(context.filesDir, file.name).apply { writeText("other") }
        assertThrows(IllegalArgumentException::class.java) { OsnUpdateInstaller.installIntent(context, other) }
    }
}
