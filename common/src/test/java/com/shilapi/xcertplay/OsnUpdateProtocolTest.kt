package com.shilapi.xcertplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OsnUpdateProtocolTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun onlyThisForksAssetsAndHttpsGitHubRedirectsAreAccepted() {
        assertTrue(OsnUpdateProtocol.repositoryAsset("https://github.com/xxxcrel/OsnPlay/releases/download/v1.5.1/app.apk"))
        assertFalse(OsnUpdateProtocol.repositoryAsset("https://github.com/shihabal3amri/DiPlay/releases/download/v1/app.apk"))
        assertFalse(OsnUpdateProtocol.repositoryAsset("http://github.com/xxxcrel/OsnPlay/releases/download/v1/app.apk"))
        assertFalse(OsnUpdateProtocol.repositoryAsset("https://github.com@other.test/xxxcrel/OsnPlay/releases/download/v1/app.apk"))
        assertTrue(OsnUpdateProtocol.transportUrl("https://release-assets.githubusercontent.com/asset"))
        assertFalse(OsnUpdateProtocol.transportUrl("https://githubusercontent.com.other.test/asset"))
    }
    @Test fun metadataRequiresTheInstalledProductAndWellFormedVersionHashAndAsset() {
        val f = OsnUpdateTestFixture(folder.root).also { it.publish() }
        val github = OsnUpdateProtocol.release(String(f.http.responses[OsnUpdateProtocol.LATEST_API]!!.second))!!
        assertEquals(12L, OsnUpdateProtocol.metadata(f.metadata.toString(), github, f.installed).versionCode)
        for ((field, value) in listOf("versionCode" to "12", "versionCode" to -1, "sha256" to "bad", "apkAsset" to "../app.apk", "formatVersion" to 2)) {
            val invalid = JSONObject(f.metadata.toString()).put(field, value)
            assertThrows(OsnUpdateException::class.java) { OsnUpdateProtocol.metadata(invalid.toString(), github, f.installed) }
        }
        val other = JSONObject(f.metadata.toString()).put("applicationId", "com.shihab.osnplay")
        assertEquals(OsnUpdateError.PACKAGE, assertThrows(OsnUpdateException::class.java) {
            OsnUpdateProtocol.metadata(other.toString(), github, f.installed)
        }.reason)
    }
    @Test fun draftsAndPrereleasesAreNotOfferedAsStableUpdates() {
        assertNull(OsnUpdateProtocol.release("""{"draft":true}"""))
        assertNull(OsnUpdateProtocol.release("""{"prerelease":true}"""))
    }
}
