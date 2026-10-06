package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OsnUpdateManagerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var f: OsnUpdateTestFixture
    @Before fun setup() { f = OsnUpdateTestFixture(folder.root).also { it.publish() } }
    @Test fun manualChecksAreCoalescedWhileBusyAndCanBeRepeatedAfterCompletion() {
        assertTrue(f.manager.check()); assertFalse(f.manager.check())
        f.worker.drain()
        assertEquals(OsnUpdatePhase.AVAILABLE, f.manager.state.phase)
        assertEquals(2, f.http.requests.size)
        assertTrue(f.manager.check()); f.worker.drain()
        assertEquals(4, f.http.requests.size)
    }
    @Test fun missingReleaseMissingMetadataAndRateLimitsHaveDistinctStates() {
        f.http.responses.clear()
        f.manager.check(); f.worker.drain(); assertEquals(OsnUpdatePhase.NO_RELEASE, f.manager.state.phase)
        f.http.responses[OsnUpdateProtocol.LATEST_API] = 200 to """{"assets":[]}""".toByteArray()
        f.manager.check(); f.worker.drain(); assertEquals(OsnUpdatePhase.NO_PACKAGE, f.manager.state.phase)
        f.http.responses[OsnUpdateProtocol.LATEST_API] = 429 to byteArrayOf()
        f.manager.check(); f.worker.drain(); assertEquals(OsnUpdateError.RATE_LIMIT, f.manager.state.error)
        assertTrue(f.manager.check()); f.worker.drain()
        assertEquals(OsnUpdateError.RATE_LIMIT, f.manager.state.error)
    }
    @Test fun equalOrOlderVersionsAndUnsupportedAndroidNeverEnableDownload() {
        f.metadata.put("versionCode", 11); f.publish()
        f.manager.check(); f.worker.drain()
        assertEquals(OsnUpdatePhase.LATEST, f.manager.state.phase); assertFalse(f.manager.download())
        f.metadata.put("versionCode", 12).put("minSdk", 29); f.publish()
        f.manager.check(); f.worker.drain()
        assertEquals(OsnUpdatePhase.INCOMPATIBLE, f.manager.state.phase); assertFalse(f.manager.download())
    }
    @Test fun downloadIsVerifiedAndCanBeReusedWithoutFetchingTheApkAgain() {
        f.manager.check(); f.worker.drain(); f.manager.download(); f.worker.drain()
        assertEquals(OsnUpdatePhase.READY, f.manager.state.phase)
        assertArrayEquals(f.apk, f.manager.state.file!!.readBytes())
        var installReady = false
        f.manager.prepareInstall { installReady = true }; f.worker.drain()
        assertTrue(installReady); assertEquals(2, f.validationCount)
        f.manager.check(); f.worker.drain(); f.manager.download(); f.worker.drain()
        assertEquals(1, f.http.requests.count { it == f.apkUrl })
    }
    @Test fun corruptTruncatedAndWronglySignedDownloadsAreNeverInstallable() {
        for ((bytes, archiveError, expected) in listOf(
            Triple(f.apk.copyOf().also { it[0] = 0 }, null, OsnUpdateError.HASH),
            Triple(byteArrayOf(1), null, OsnUpdateError.SIZE),
            Triple(f.apk, OsnUpdateError.SIGNATURE, OsnUpdateError.SIGNATURE))) {
            f.manager.check(); f.worker.drain()
            f.http.responses[f.apkUrl] = 200 to bytes; f.archiveError = archiveError
            f.manager.download(); f.worker.drain()
            assertEquals(expected, f.manager.state.error)
            assertNull(f.manager.state.file)
            assertTrue(folder.root.listFiles()!!.isEmpty())
            f.archiveError = null; f.publish()
        }
    }
    @Test fun cancellingAnInFlightReadRemovesPartialDataAndDoesNotInstall() {
        f.manager.check(); f.worker.drain()
        f.http.reading = { url -> if (url == f.apkUrl) f.manager.cancel() }
        f.manager.download(); assertFalse(f.manager.download()); f.worker.drain()
        assertEquals(OsnUpdatePhase.CANCELLED, f.manager.state.phase)
        assertTrue(folder.root.listFiles()!!.isEmpty()); assertFalse(f.manager.prepareInstall { fail("Cancelled update installed") })
        f.http.reading = null; assertTrue(f.manager.download()); f.worker.drain()
        assertEquals(OsnUpdatePhase.READY, f.manager.state.phase)
    }
    @Test fun theReadyFileIsRecheckedAfterReturningFromInstallationPermissionSettings() {
        f.manager.check(); f.worker.drain(); f.manager.download(); f.worker.drain()
        f.manager.state.file!!.writeBytes(byteArrayOf(1))
        var installed = false
        f.manager.prepareInstall { installed = true }; f.worker.drain()
        assertFalse(installed); assertEquals(OsnUpdatePhase.ERROR, f.manager.state.phase)
        assertNull(f.manager.state.file)
    }
    @Test fun disposedWindowsNeverReceiveQueuedResults() {
        val main = OsnUpdateTestExecutor()
        val fixture = OsnUpdateTestFixture(folder.root, main).also { it.publish() }
        var callbacks = 0
        val subscription = fixture.manager.observe { callbacks++ }
        fixture.manager.check(); subscription.close(); fixture.worker.drain(); main.drain()
        assertEquals(0, callbacks); assertEquals(OsnUpdatePhase.AVAILABLE, fixture.manager.state.phase)
    }
}
