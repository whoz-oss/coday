package io.whozoss.factory.web

import java.net.InetAddress

/**
 * An IPv4 or IPv6 network in CIDR notation (e.g. `172.16.0.0/12`, `fd00::/8`).
 * A bare address (no `/prefix`) is a single-host network (`/32` or `/128`).
 *
 * Used to declare which source networks the loopback-dev trust path applies to
 * (`factory.security.trusted-cidrs`). Only IP *literals* are accepted: no DNS
 * resolution is ever performed, neither for the configuration nor for the
 * caller's socket address.
 */
class CidrBlock private constructor(
    private val network: ByteArray,
    private val prefixLength: Int,
    private val spec: String,
) {

    /** True when [address] (an IP literal, optionally bracketed / zone-scoped) is inside this network. */
    fun contains(address: String?): Boolean {
        val bytes = literalBytes(address) ?: return false
        if (bytes.size != network.size) return false
        val fullBytes = prefixLength / 8
        for (i in 0 until fullBytes) {
            if (bytes[i] != network[i]) return false
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    override fun toString(): String = spec

    companion object {
        private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6_LITERAL = Regex("^[0-9a-fA-F:.]+$")

        /** Parses `address[/prefix]`; throws [IllegalArgumentException] on any malformed input. */
        fun parse(raw: String): CidrBlock {
            val spec = raw.trim()
            val address = spec.substringBefore('/')
            val bytes = literalBytes(address)
                ?: throw IllegalArgumentException("Invalid CIDR '$raw': '$address' is not an IP address literal")
            val maxPrefix = bytes.size * 8
            val prefix = if (spec.contains('/')) {
                spec.substringAfter('/').toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid CIDR '$raw': prefix length is not a number")
            } else {
                maxPrefix
            }
            require(prefix in 0..maxPrefix) { "Invalid CIDR '$raw': prefix length must be between 0 and $maxPrefix" }
            return CidrBlock(bytes, prefix, spec)
        }

        /** Parses a list of CIDRs, ignoring blank entries. */
        fun parseAll(specs: List<String>): List<CidrBlock> =
            specs.map { it.trim() }.filter { it.isNotEmpty() }.map(::parse)

        private fun literalBytes(raw: String?): ByteArray? {
            if (raw == null) return null
            val normalized = raw.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            val isLiteral = IPV4_LITERAL.matches(normalized) ||
                (normalized.contains(':') && IPV6_LITERAL.matches(normalized))
            if (!isLiteral) return null
            // A literal never triggers a DNS lookup. IPv4-mapped IPv6 (::ffff:a.b.c.d)
            // is returned by the JDK as a 4-byte IPv4 address, which is what we want.
            return runCatching { InetAddress.getByName(normalized).address }.getOrNull()
        }
    }
}
