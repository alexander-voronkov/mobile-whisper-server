package com.example.whisperserver.network

import java.net.Inet4Address
import java.net.NetworkInterface

/** A network address the server can bind to / be reached on. */
data class HostOption(
    val address: String,
    val label: String,
    val isTailscale: Boolean = false,
    val isWildcard: Boolean = false,
)

/**
 * Enumerates local network interfaces to offer bind targets in the UI.
 *
 * Always offers `0.0.0.0` (all interfaces). Additionally surfaces any
 * Tailscale address (CGNAT range 100.64.0.0/10, i.e. 100.64.x.x – 100.127.x.x)
 * and other non-loopback IPv4 addresses so the user can pick a specific one.
 */
object TailscaleDetector {

    private const val WILDCARD = "0.0.0.0"

    /** Returns true for the Tailscale CGNAT range 100.64.0.0/10. */
    fun isTailscaleAddress(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }

    /** All usable non-loopback IPv4 addresses on the device. */
    fun localIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { result.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            // Interface enumeration can fail on restricted devices; return what we have.
        }
        return result.distinct()
    }

    /** The first detected Tailscale IP, or null if the device is not on Tailscale. */
    fun tailscaleAddress(): String? = localIpv4Addresses().firstOrNull { isTailscaleAddress(it) }

    /**
     * Build the ordered list of host options for the dropdown:
     * wildcard first, then any Tailscale IPs, then remaining local IPs.
     */
    fun hostOptions(): List<HostOption> {
        val options = mutableListOf(
            HostOption(WILDCARD, "All interfaces (0.0.0.0)", isWildcard = true),
        )
        val locals = localIpv4Addresses()
        val (tailscale, others) = locals.partition { isTailscaleAddress(it) }
        tailscale.forEach { options.add(HostOption(it, "Tailscale ($it)", isTailscale = true)) }
        others.forEach { options.add(HostOption(it, "Local ($it)")) }
        return options
    }

    /** A friendly label for an address that may or may not be in [hostOptions]. */
    fun labelFor(address: String): String = when {
        address == WILDCARD -> "All interfaces (0.0.0.0)"
        isTailscaleAddress(address) -> "Tailscale ($address)"
        else -> address
    }
}
