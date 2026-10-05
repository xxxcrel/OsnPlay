package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotAddressMode
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class ManualHotspotInterfacesTest {
    private val vlan = network("eth0.3", "192.168.10.1", "fe80::1234")
    private val station = network("wlan0", "192.168.1.20", "fe80::2345")
    private val ap = network("wlan1", "192.168.43.1", "fe80::3456")

    @Test fun osnIdleVlanIsNotMistakenForHotspotWithoutSystemEvidence() {
        assertNull(select(listOf(vlan, station), stations = setOf("wlan0")))
        assertEquals(ap, select(listOf(vlan, station, ap), stations = setOf("wlan0")))
    }

    @Test fun confirmedVendorVlanAndBridgeRemainSupported() {
        assertEquals(vlan, select(listOf(vlan, ap), tethered = setOf("eth0.3")))
        val bridge = network("br0", "192.168.50.1", "fe80::4567")
        // The system's hotspot evidence wins even when also reported as a Wi-Fi network.
        assertEquals(bridge, select(listOf(bridge, ap), tethered = setOf("br0"), stations = setOf("br0")))
    }

    @Test fun confirmedHotspotWithWrongAddressFamilyDoesNotSelectAnUnrelatedInterface() {
        val ipv6Only = network("ap0", "fe80::4567")
        assertNull(select(listOf(ipv6Only, ap), tethered = setOf("ap0"), mode = ManualHotspotAddressMode.IPV4))
    }

    @Test fun explicitOverrideSupportsVendorNamesAndNeverSilentlyFallsBack() {
        assertEquals(vlan, select(listOf(vlan, ap), preferred = "eth0.3"))
        assertNull(select(listOf(ap), preferred = "eth0.3"))
        assertNull(select(listOf(network("eth0.3", "fe80::1234"), ap),
            preferred = "eth0.3", mode = ManualHotspotAddressMode.IPV4))
    }

    @Test fun android9AutoUsesIpv4WhileNewerDefaultRetainsScopedIpv6() {
        assertEquals(InetAddress.getByName("192.168.43.1"), manualHotspotHostAddress(ap, ManualHotspotAddressMode.AUTO, true))
        val ipv6 = manualHotspotHostAddress(ap, ManualHotspotAddressMode.AUTO, false) as Inet6Address
        assertEquals(ap.index, ipv6.scopeId)
        assertTrue(ipv6.isLinkLocalAddress)
    }

    @Test fun explicitFamilyModesDoNotChangeFamilyWhenUnavailable() {
        assertNull(manualHotspotHostAddress(network("ap0", "fe80::1"), ManualHotspotAddressMode.IPV4, false))
        assertNull(manualHotspotHostAddress(network("ap0", "192.168.43.1"), ManualHotspotAddressMode.IPV6, true))
        val wrongScope = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 2)
        val iface = ManualHotspotInterface("ap0", 7, listOf(wrongScope))
        assertEquals(7, (manualHotspotHostAddress(iface, ManualHotspotAddressMode.IPV6, true) as Inet6Address).scopeId)
    }

    @Test fun wildcardMulticastLoopbackAndUnscopedIpv6AreNotUsable() {
        val invalid = network("ap0", "0.0.0.0", "127.0.0.1", "224.0.0.251", "169.254.1.1", "::1", "2001:db8::1")
        assertNull(manualHotspotHostAddress(invalid, ManualHotspotAddressMode.AUTO, true))
        assertNull(manualHotspotHostAddress(network("ap0", "fe80::1").copy(index = 0), ManualHotspotAddressMode.IPV6, true))
    }

    private fun network(name: String, vararg addresses: String) =
        ManualHotspotInterface(name, 7, addresses.map(InetAddress::getByName))

    private fun select(
        candidates: List<ManualHotspotInterface>,
        tethered: Set<String>? = null,
        stations: Set<String> = emptySet(),
        preferred: String? = null,
        mode: ManualHotspotAddressMode = ManualHotspotAddressMode.AUTO,
    ) = selectManualHotspotInterface(candidates, tethered, stations, preferred, mode, preferIpv4 = true)
}
