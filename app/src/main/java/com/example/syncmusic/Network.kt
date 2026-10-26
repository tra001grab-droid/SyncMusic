package com.example.syncmusic

import java.net.Inet4Address
import java.net.NetworkInterface

object Network {
    // Mobile-data / VPN / virtual interfaces: other phones on the Wi-Fi or hotspot can't reach these.
    private val excluded = listOf("rmnet", "ccmni", "pdp", "v4-", "clat", "dummy", "tun", "ppp", "ipsec", "lo")

    /** This phone's IPv4 addresses on local networks, Wi-Fi/hotspot interfaces first. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && excluded.none { p -> it.name.startsWith(p) } }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("swlan") || it.name.startsWith("ap")) 0 else 1 }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress!! } }
            .distinct()
    }.getOrDefault(emptyList())

    /** Orders [candidates] so addresses in the same /24 as one of ours are tried first. */
    fun preferSameSubnet(candidates: List<String>): List<String> {
        val ours = lanAddresses().map { it.substringBeforeLast('.') }.toSet()
        return candidates.distinct().sortedBy { if (it.substringBeforeLast('.') in ours) 0 else 1 }
    }
}
