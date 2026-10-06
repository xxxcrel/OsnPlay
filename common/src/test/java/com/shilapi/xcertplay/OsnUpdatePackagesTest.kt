package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OsnUpdatePackagesTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun archivePackageVersionAndroidAndSigningMustAllMatchBeforeInstallation() {
        val app = RuntimeEnvironment.getApplication()
        val pm = mock(PackageManager::class.java)
        val context = object : ContextWrapper(app) {
            override fun getPackageName() = OsnUpdateProtocol.APPLICATION_ID
            override fun getPackageManager(): PackageManager = pm
        }
        fun info(code: Long, name: String, certificate: String) = PackageInfo().apply {
            packageName = OsnUpdateProtocol.APPLICATION_ID; setLongVersionCode(code); versionName = name
            applicationInfo = ApplicationInfo().apply { minSdkVersion = 28 }
            signingInfo = mock(SigningInfo::class.java).also {
                `when`(it.apkContentsSigners).thenReturn(arrayOf(Signature(certificate.toByteArray())))
            }
        }
        val installed = info(11, "1.5.0", "local-key")
        val archive = info(12, "1.5.1", "local-key")
        val apk = folder.newFile("candidate.apk")
        `when`(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)).thenReturn(installed)
        `when`(pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)).thenReturn(archive)
        val packages = OsnUpdatePackages(context)
        val release = OsnUpdateRelease(12, "1.5.1", 28, "a".repeat(64), OsnUpdateAsset("app.apk", "", 1), "")
        packages.verifyArchive(apk, release)
        archive.packageName = "com.other.application"
        assertEquals(OsnUpdateError.PACKAGE, assertThrows(OsnUpdateException::class.java) { packages.verifyArchive(apk, release) }.reason)
        archive.packageName = context.packageName; archive.setLongVersionCode(11)
        assertEquals(OsnUpdateError.VERSION, assertThrows(OsnUpdateException::class.java) { packages.verifyArchive(apk, release) }.reason)
        archive.setLongVersionCode(12); archive.applicationInfo!!.minSdkVersion = 29
        assertEquals(OsnUpdateError.ANDROID_VERSION, assertThrows(OsnUpdateException::class.java) { packages.verifyArchive(apk, release) }.reason)
        archive.applicationInfo!!.minSdkVersion = 28
        `when`(archive.signingInfo!!.apkContentsSigners).thenReturn(arrayOf(Signature("different-key".toByteArray())))
        assertEquals(OsnUpdateError.SIGNATURE, assertThrows(OsnUpdateException::class.java) { packages.verifyArchive(apk, release) }.reason)
    }
}
