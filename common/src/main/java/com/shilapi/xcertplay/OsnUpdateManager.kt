package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class OsnUpdatePhase {
    IDLE, CHECKING, AVAILABLE, LATEST, NO_RELEASE, NO_PACKAGE, INCOMPATIBLE,
    DOWNLOADING, VERIFYING, PREPARING_INSTALL, READY, CANCELLING, CANCELLED, ERROR,
}
internal data class OsnUpdateState(val phase: OsnUpdatePhase = OsnUpdatePhase.IDLE,
    val release: OsnUpdateRelease? = null, val bytes: Long = 0, val file: File? = null,
    val error: OsnUpdateError? = null)
/** Process-owned work with application context only; windows subscribe and can detach independently. */
internal class OsnUpdateManager(private val http: OsnUpdateHttp, private val installed: () -> OsnInstalledApp,
    private val files: OsnUpdateFiles,
    private val worker: Executor, private val main: Executor, private val now: () -> Long = System::currentTimeMillis,
) {
    private class Job { val cancelled = AtomicBoolean(false) }
    private class Observer(val changed: (OsnUpdateState) -> Unit) { val active = AtomicBoolean(true) }
    private val lock = Any()
    private val observers = mutableSetOf<Observer>()
    private var job: Job? = null
    @Volatile var state = OsnUpdateState()
        private set
    fun observe(changed: (OsnUpdateState) -> Unit): Closeable {
        val observer = Observer(changed)
        synchronized(lock) { observers += observer }
        main.execute { if (observer.active.get()) changed(state) }
        return Closeable { observer.active.set(false); synchronized(lock) { observers -= observer } }
    }
    private fun publish(value: OsnUpdateState) {
        val listeners = synchronized(lock) { state = value; observers.toList() }
        deliver(value, listeners)
    }
    private fun deliver(value: OsnUpdateState, listeners: List<Observer>) {
        listeners.forEach { listener -> main.execute {
            if (listener.active.get() && state === value) listener.changed(value)
        } }
    }
    private fun progress(expected: Job, value: OsnUpdateState) {
        val listeners = synchronized(lock) {
            if (job !== expected || expected.cancelled.get()) return
            state = value; observers.toList()
        }
        deliver(value, listeners)
    }
    private fun begin(): Job? = synchronized(lock) {
        if (job != null) null else Job().also { job = it }
    }
    private fun finish(expected: Job, result: OsnUpdateState): Boolean {
        val (value, listeners) = synchronized(lock) {
            if (job !== expected) return false
            val value = if (expected.cancelled.get()) OsnUpdateState(OsnUpdatePhase.CANCELLED, result.release) else result
            job = null; state = value
            value to observers.toList()
        }
        deliver(value, listeners)
        return !expected.cancelled.get()
    }

    /** Called only by the user's Check for updates action. */
    fun check(): Boolean {
        val current = begin() ?: return false
        publish(state.copy(phase = OsnUpdatePhase.CHECKING, error = null))
        worker.execute {
            val result = try {
                val response = http.json(OsnUpdateProtocol.LATEST_API, OsnUpdateProtocol.MAX_JSON_BYTES, missingAllowed = true)
                val github = response?.let(OsnUpdateProtocol::release)
                val metadata = github?.assets?.singleOrNull { it.name == "update.json" }
                when {
                    github == null -> OsnUpdateState(OsnUpdatePhase.NO_RELEASE)
                    metadata == null -> OsnUpdateState(OsnUpdatePhase.NO_PACKAGE)
                    else -> {
                        if (!OsnUpdateProtocol.repositoryAsset(metadata.url) || metadata.size !in 1..OsnUpdateProtocol.MAX_METADATA_BYTES.toLong())
                            throw OsnUpdateException(OsnUpdateError.INVALID_METADATA)
                        val identity = installed()
                        val json = http.json(metadata.url, OsnUpdateProtocol.MAX_METADATA_BYTES)!!
                        val release = OsnUpdateProtocol.metadata(json, github, identity)
                        OsnUpdateState(when {
                            release.versionCode <= identity.versionCode -> OsnUpdatePhase.LATEST
                            identity.sdk < release.minSdk -> OsnUpdatePhase.INCOMPATIBLE
                            else -> OsnUpdatePhase.AVAILABLE
                        }, release)
                    }
                }
            } catch (error: Exception) { state.copy(phase = OsnUpdatePhase.ERROR, error = reason(error)) }
            finish(current, result)
        }
        return true
    }

    fun download(): Boolean {
        val release = state.release ?: return false
        if (state.phase !in setOf(OsnUpdatePhase.AVAILABLE, OsnUpdatePhase.CANCELLED, OsnUpdatePhase.ERROR)) return false
        val current = begin() ?: return false
        publish(OsnUpdateState(OsnUpdatePhase.DOWNLOADING, release))
        worker.execute {
            var lastBytes = 0L; var lastAt = 0L
            val result = try {
                val file = files.download(release, http, current.cancelled::get,
                    progress = { bytes ->
                        if (!current.cancelled.get() && (bytes == release.apk.size || bytes - lastBytes >= 256 * 1024 && now() - lastAt >= 200)) {
                            lastBytes = bytes; lastAt = now()
                            progress(current, OsnUpdateState(OsnUpdatePhase.DOWNLOADING, release, bytes))
                        }
                    }, verifying = { progress(current, OsnUpdateState(OsnUpdatePhase.VERIFYING, release)) })
                OsnUpdateState(OsnUpdatePhase.READY, release, release.apk.size, file)
            } catch (error: Exception) { OsnUpdateState(OsnUpdatePhase.ERROR, release, error = reason(error)) }
            finish(current, result)
        }
        return true
    }
    fun cancel() {
        val (value, listeners) = synchronized(lock) {
            val current = job ?: return
            if (state.phase !in setOf(OsnUpdatePhase.DOWNLOADING, OsnUpdatePhase.VERIFYING)) return
            current.cancelled.set(true)
            val value = state.copy(phase = OsnUpdatePhase.CANCELLING)
            state = value
            value to observers.toList()
        }
        deliver(value, listeners)
    }
    fun prepareInstall(ready: (File) -> Unit): Boolean {
        val snapshot = state
        val release = snapshot.release ?: return false
        val file = snapshot.file ?: return false
        if (snapshot.phase != OsnUpdatePhase.READY) return false
        val current = begin() ?: return false
        publish(snapshot.copy(phase = OsnUpdatePhase.PREPARING_INSTALL))
        worker.execute {
            try {
                files.verify(file, release) // Recheck after permission/settings navigation or cache eviction.
                if (finish(current, snapshot)) main.execute {
                    if (!current.cancelled.get() && state.file == file && state.phase == OsnUpdatePhase.READY) ready(file)
                }
            } catch (error: Exception) {
                file.delete()
                finish(current, OsnUpdateState(OsnUpdatePhase.ERROR, release, error = reason(error)))
            }
        }
        return true
    }
    private fun reason(error: Exception): OsnUpdateError = (error as? OsnUpdateException)?.reason ?: OsnUpdateError.NETWORK

    companion object {
        @Volatile private var instance: OsnUpdateManager? = null
        fun forApp(context: Context): OsnUpdateManager = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val packages = OsnUpdatePackages(app)
                val handler = Handler(Looper.getMainLooper())
                OsnUpdateManager(OsnGitHubHttp(), packages::installed,
                    OsnUpdateFiles({ File(app.cacheDir, "updates") }, packages::verifyArchive),
                    Executors.newSingleThreadExecutor { Thread(it, "osnplay-updates").apply { isDaemon = true } },
                    Executor { handler.post(it) }).also { instance = it }
            }
        }
    }
}
