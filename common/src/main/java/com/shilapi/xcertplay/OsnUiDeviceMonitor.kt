package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.ManualHotspotInterface
import com.shilapi.xcertplay.network.ManualHotspotInterfaces
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Read-only system-service results. A missing snapshot means unknown, not disabled. */
internal data class OsnUiDeviceSnapshot(
    val bluetoothEnabled: Boolean? = null,
    val hotspotEnabled: Boolean? = null,
    val interfaces: List<ManualHotspotInterface> = emptyList(),
    val grantedPermissions: Set<String> = emptySet(),
    val hotspotControlPermitted: Boolean = false,
    val bydHeadUnit: Boolean = false,
    val audioOutputTypes: List<Int> = emptyList(),
) {
    companion object {
        fun read(context: Context): OsnUiDeviceSnapshot {
            val app = context.applicationContext
            val interfaces = ManualHotspotInterfaces.available()
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }.filter { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()
            val bluetooth = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
            val outputs = runCatching { app.getSystemService(android.media.AudioManager::class.java)
                ?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type }?.distinct().orEmpty() }.getOrDefault(emptyList())
            return OsnUiDeviceSnapshot(bluetooth, CarHotspotStatus.isEnabled(app), interfaces, permissions,
                CarHotspotTethering.permitted(app), app.resources.getBoolean(com.shilapi.xcertplay.host.R.bool.config_byd_features) && CarHotspotSetup.isBydHeadUnit(app), outputs)
        }
    }
}

/** One background request at a time; navigation/status ticks never wait for Binder. */
internal class OsnUiDeviceMonitor(
    private val source: () -> OsnUiDeviceSnapshot,
    private val main: Executor,
    changed: (OsnUiDeviceSnapshot) -> Unit,
    private val worker: Executor = background,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val intervalMillis: Long = 10_000,
) : AutoCloseable {
    private val lock = Any()
    private var changed: ((OsnUiDeviceSnapshot) -> Unit)? = changed
    private var closed = false
    private var busy = false
    private var completedAt: Long? = null
    private var count = 0
    private var lastMillis = 0L
    private var maxMillis = 0L
    private var failure: String? = null
    @Volatile var latest: OsnUiDeviceSnapshot? = null
        private set

    fun request(force: Boolean = false): Boolean {
        synchronized(lock) {
            if (closed || busy || !force && completedAt?.let { clock() - it < intervalMillis } == true) return false
            busy = true
        }
        worker.execute {
            synchronized(lock) { if (closed) { busy = false; return@execute } }
            val started = clock()
            val result = runCatching(source)
            val snapshot = result.getOrNull()
            synchronized(lock) {
                busy = false; completedAt = clock(); count++
                lastMillis = (clock() - started).coerceAtLeast(0); maxMillis = maxOf(maxMillis, lastMillis)
                failure = result.exceptionOrNull()?.javaClass?.simpleName
                if (!closed && snapshot != null) latest = snapshot
            }
            if (snapshot != null) main.execute {
                val callback = synchronized(lock) { if (!closed && latest === snapshot) changed else null }
                callback?.invoke(snapshot)
            }
        }
        return true
    }

    fun report(): String = synchronized(lock) {
        "backgroundQueries=$count inFlight=$busy lastQueryMs=$lastMillis maxQueryMs=$maxMillis result=${failure ?: if (latest == null) "unknown" else "ready"}"
    }

    override fun close() = synchronized(lock) { closed = true; changed = null }

    companion object {
        private val background = Executors.newSingleThreadExecutor { task ->
            Thread(task, "osnplay-ui-status").apply { isDaemon = true }
        }
    }
}
