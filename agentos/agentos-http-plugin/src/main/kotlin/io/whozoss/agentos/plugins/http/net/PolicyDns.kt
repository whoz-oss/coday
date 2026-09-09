package io.whozoss.agentos.plugins.http.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS resolver that keeps only the addresses the [OutboundUrlPolicy] allows, so that the addresses actually
 * connected to are the ones checked: a public host name resolving (or rebinding) to a private range is
 * refused with an [UnknownHostException].
 */
class PolicyDns(
    private val policy: OutboundUrlPolicy,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val allowed = delegate.lookup(hostname).filterNot(policy::isDisallowedAddress)
        if (allowed.isEmpty()) throw UnknownHostException("host '$hostname' resolves only to disallowed addresses")
        return allowed
    }
}
