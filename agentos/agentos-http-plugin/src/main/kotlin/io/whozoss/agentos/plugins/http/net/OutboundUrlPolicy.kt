package io.whozoss.agentos.plugins.http.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * Static (DNS-free) policy deciding which URLs the plugin may call.
 *
 * A URL passes when it is absolute, uses `https`, carries no userinfo, has a non-blank host
 * that is neither `localhost` nor a literal IP address of a loopback, unspecified, "this network"
 * (0.0.0.0/8), link-local, site-local (RFC 1918), carrier-grade NAT (100.64/10) or IPv6 unique-local
 * (fc00::/7) range, including such addresses written as IPv4-mapped IPv6 literals, and is accepted by the
 * OkHttp parser (a port above 65535 passes [URI] but not the HTTP client).
 *
 * Host names are NOT resolved here: [isDisallowedAddress] is the hook the HTTP layer applies
 * to every resolved address at connection time.
 *
 * A [UrlCheck.Rejected] reason never echoes the URL itself: userinfo or a query string may carry
 * credentials and the reason ends up in exception messages and logs.
 *
 * @param allowUnsecure accepts plain `http` URLs and relaxes restricted-address checks so local and
 *   private-network destinations can be reached. The OkHttp DNS policy is shared by HTTP and HTTPS
 *   and cannot distinguish the request scheme, so this opt-in deliberately relaxes address checks
 *   for both schemes. All other URL protections remain active.
 */
open class OutboundUrlPolicy(private val allowUnsecure: Boolean = false) {

    open fun validate(url: String): UrlCheck {
        val uri = parse(url) ?: return UrlCheck.Rejected("is not a valid URL")
        if (!uri.isAbsolute) return UrlCheck.Rejected("must be an absolute URL")
        if (uri.rawUserInfo != null) return UrlCheck.Rejected("must not contain userinfo (credentials)")
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return UrlCheck.Rejected("has no host")
        val isUnsecureHttp = allowUnsecure && uri.scheme.equals(HTTP, ignoreCase = true)
        if (!uri.scheme.equals(HTTPS, ignoreCase = true) && !isUnsecureHttp) {
            return UrlCheck.Rejected("must use the https scheme, got '${uri.scheme}'")
        }
        val hostCheck = checkHost(uri, host, allowRestrictedAddress = allowUnsecure)
        if (hostCheck is UrlCheck.Rejected) return hostCheck
        return checkClientAccepts(url, uri)
    }

    /**
     * OkHttp parses more strictly than [URI] (a port above 65535, for instance): a URL it refuses would throw
     * when the request is built, past every configuration check, so it is refused here with OkHttp's reason,
     * which names the offending host or port and never the whole URL.
     */
    private fun checkClientAccepts(url: String, uri: URI): UrlCheck =
        try {
            url.toHttpUrl()
            UrlCheck.Ok(uri)
        } catch (e: IllegalArgumentException) {
            UrlCheck.Rejected("is not a URL the HTTP client accepts (${e.message})")
        }

    /**
     * Returns true when [address] belongs to a range the plugin must never connect to.
     * Reused by the DNS hook so that a public host name resolving to an internal address is refused.
     */
    open fun isDisallowedAddress(address: InetAddress): Boolean {
        if (allowUnsecure) return false
        val effective = unmapIpv4(address)
        return effective.isLoopbackAddress ||
            effective.isAnyLocalAddress ||
            isThisNetwork(effective) ||
            effective.isLinkLocalAddress ||
            effective.isSiteLocalAddress ||
            isCarrierGradeNat(effective) ||
            isUniqueLocal(effective)
    }

    private fun checkHost(uri: URI, host: String, allowRestrictedAddress: Boolean): UrlCheck {
        if (host.equals(LOCALHOST, ignoreCase = true)) {
            return if (allowRestrictedAddress) UrlCheck.Ok(uri) else UrlCheck.Rejected("'localhost' is not allowed")
        }
        return when (val literal = classifyLiteral(host)) {
            HostLiteral.NotLiteral -> UrlCheck.Ok(uri)
            HostLiteral.Malformed -> UrlCheck.Rejected("host '$host' is not a valid IP literal")
            is HostLiteral.Literal ->
                if (!allowRestrictedAddress && isDisallowedAddress(literal.address)) {
                    UrlCheck.Rejected("host '$host' is a private, loopback or link-local address")
                } else {
                    UrlCheck.Ok(uri)
                }
        }
    }

    private fun parse(url: String): URI? =
        try {
            URI(url)
        } catch (e: URISyntaxException) {
            null
        }

    /**
     * Classifies a host that looks like an IP literal (with or without IPv6 brackets) without any lookup:
     * [InetAddress.ofLiteral] parses strictly and never consults the name service, unlike `getByName`,
     * which resolves a malformed IPv4-looking host as a name. The zone id of a scoped IPv6 literal
     * (`fe80::1%eth0`, `%25` once URL-encoded) is dropped before parsing so the address itself is classified.
     * A literal the JDK cannot parse is [HostLiteral.Malformed]: a deny-list policy rejects what it cannot classify.
     */
    private fun classifyLiteral(host: String): HostLiteral {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (!isIpLiteral(bare)) return HostLiteral.NotLiteral
        return try {
            HostLiteral.Literal(InetAddress.ofLiteral(bare.substringBefore(IPV6_ZONE_DELIMITER)))
        } catch (e: IllegalArgumentException) {
            HostLiteral.Malformed
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }

    private fun unmapIpv4(address: InetAddress): InetAddress {
        if (address !is Inet6Address) return address
        val bytes = address.address
        val isMapped = bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() &&
            bytes[11] == 0xFF.toByte()
        return if (isMapped) InetAddress.getByAddress(bytes.copyOfRange(12, 16)) else address
    }

    /** 0.0.0.0/8, "this network" (RFC 1122): only 0.0.0.0 itself is flagged by `isAnyLocalAddress`. */
    private fun isThisNetwork(address: InetAddress): Boolean =
        address is Inet4Address && address.address[0] == 0.toByte()

    private fun isCarrierGradeNat(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        return bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xC0) == 0x40
    }

    private fun isUniqueLocal(address: InetAddress): Boolean =
        address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC

    private sealed interface HostLiteral {
        data object NotLiteral : HostLiteral
        data object Malformed : HostLiteral
        data class Literal(val address: InetAddress) : HostLiteral
    }

    companion object {
        private const val HTTPS = "https"
        private const val HTTP = "http"
        private const val LOCALHOST = "localhost"
        private const val IPV6_ZONE_DELIMITER = '%'
    }
}
