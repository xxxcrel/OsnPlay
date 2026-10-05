package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.shilapi.xcertplay.orchestration.ManualHotspotAddressMode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

data class ManualHotspotInterface(
    val name: String,
    val index: Int,
    val addresses: List<InetAddress>,
)

/** Read-only interface discovery. Unknown Ethernet/VLAN interfaces require system evidence or selection. */
object ManualHotspotInterfaces {
    fun isValidName(name: String): Boolean = Regex("[A-Za-z0-9_.-]{1,15}").matches(name)

    fun available(): List<ManualHotspotInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { network ->
            try {
                if (!network.isUp || network.isLoopback || excluded(network.name)) null
                else ManualHotspotInterface(network.name, network.index, network.inetAddresses.toList())
                    .takeIf { manualHotspotHostAddress(it, ManualHotspotAddressMode.AUTO, true) != null }
            } catch (_: SocketException) { null }
        }.sortedBy { it.name }
    } catch (_: SocketException) { emptyList() }

    internal fun stationInterfaces(context: Context): Set<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        return runCatching {
            manager.allNetworks.mapNotNull { network ->
                if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                    manager.getLinkProperties(network)?.interfaceName else null
            }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Hidden on stock Android; vendor firmware may expose it. Failure only disables this hint. */
    @SuppressLint("PrivateApi")
    internal fun tetheredInterfaces(context: Context): Set<String>? {
        val service = if (Build.VERSION.SDK_INT >= 30) context.getSystemService("tethering") else null
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        for (target in listOfNotNull(service, connectivity)) {
            val names = runCatching {
                (target.javaClass.getMethod("getTetheredIfaces").invoke(target) as? Array<*>)
                    ?.filterIsInstance<String>()?.toSet()
            }.getOrNull()
            if (names != null) return names
        }
        return null
    }

    private fun excluded(name: String): Boolean = listOf(
        "lo", "dummy", "rmnet", "r_rmnet", "tun", "ppp", "sit", "ip6", "bond",
    ).any(name::startsWith)
}

internal fun manualHotspotHostAddress(
    network: ManualHotspotInterface,
    mode: ManualHotspotAddressMode,
    preferIpv4: Boolean,
): InetAddress? {
    val ipv4 = network.addresses.filterIsInstance<Inet4Address>().firstOrNull {
        !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    val ipv6 = network.addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }
        ?.takeIf { network.index > 0 }
        ?.let { Inet6Address.getByAddress(null, it.address, network.index) }
    return when (mode) {
        ManualHotspotAddressMode.IPV4 -> ipv4
        ManualHotspotAddressMode.IPV6 -> ipv6
        ManualHotspotAddressMode.AUTO -> if (preferIpv4) ipv4 ?: ipv6 else ipv6 ?: ipv4
    }
}

internal fun selectManualHotspotInterface(
    candidates: List<ManualHotspotInterface>,
    tethered: Set<String>?,
    stations: Set<String>,
    preferredName: String?,
    mode: ManualHotspotAddressMode,
    preferIpv4: Boolean,
): ManualHotspotInterface? {
    val usable = candidates.filter { manualHotspotHostAddress(it, mode, preferIpv4) != null }
    // Never silently substitute another interface for an explicit choice.
    if (!preferredName.isNullOrBlank()) return usable.firstOrNull { it.name == preferredName }
    val confirmed = usable.filter { it.name in tethered.orEmpty() }
    if (!tethered.isNullOrEmpty()) return confirmed.maxByOrNull { manualInterfaceScore(it.name) }
    // Do not guess from arbitrary private addresses: the selected eth0.3 in the OSN report
    // had no receive activity and no successful discovery or TCP connection.
    // A Wi-Fi station can also have a private IP, so exclude actual station links, not the
    // active/default network (which some vendors report as the hotspot itself).
    return usable.filter { it.name !in stations && manualInterfaceScore(it.name) > 0 }
        .maxByOrNull { manualInterfaceScore(it.name) }
}

private fun manualInterfaceScore(name: String): Int = when {
    name.startsWith("ap") || name.contains("softap", ignoreCase = true) -> 100
    name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("swlan") -> 70
    name.startsWith("p2p") -> 60
    else -> 0
}
