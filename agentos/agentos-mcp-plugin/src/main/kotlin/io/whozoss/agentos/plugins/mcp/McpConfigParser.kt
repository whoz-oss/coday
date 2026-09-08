package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.type.LogicalType
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * Parses and validates a [JsonNode] config into a [McpServerConfig].
 *
 * Delegates structural mapping to Jackson, then validates business rules:
 *
 * - Exactly one of `command` (stdio transport) or `url` (HTTP transport) must be present.
 * - `command` and `url` must be non-blank.
 * - `args` entries must not be blank.
 * - `url` must be an absolute `http`/`https` URL with a host, without embedded userinfo, and
 *   must not target `localhost` or a literal loopback / link-local / site-local / unique-local /
 *   shared-address-space (CGNAT) / wildcard IP. Only literal IPs are inspected — no DNS
 *   resolution happens at parse time.
 * - `authToken`, when present, must be non-blank. Combined with a plain `http` url it is
 *   accepted but logged as a warning (credential over cleartext transport).
 * - Timeout values, when provided, must be positive.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation. Messages
 * never echo the `authToken`.
 *
 * ## Security / Trust Boundary
 *
 * For **stdio** transport, [McpServerConfig.command], [McpServerConfig.args] and
 * [McpServerConfig.env] are used to spawn an arbitrary child process on the host.
 * Configuring an `MCP_STDIO` integration is therefore equivalent to granting
 * **remote code execution** on the server.
 *
 * Access to `MCP_STDIO` integration creation MUST be restricted to platform
 * administrators. No executable allow-list is enforced at the plugin level;
 * authorization is delegated to the integration management layer.
 */
object McpConfigParser {

    private val logger = KotlinLogging.logger {}

    private val SUPPORTED_URL_SCHEMES = setOf("http", "https")

    /** First byte of an IPv6 unique-local address is `1111110x` (`fc` or `fd`). */
    private const val UNIQUE_LOCAL_PREFIX_MASK = 0xFE
    private const val UNIQUE_LOCAL_PREFIX = 0xFC

    /** IPv4 shared address space `100.64.0.0/10`: first byte 100, second byte `01xxxxxx` (64..127). */
    private const val SHARED_ADDRESS_FIRST_BYTE = 100
    private const val SHARED_ADDRESS_SECOND_BYTE_MASK = 0xC0
    private const val SHARED_ADDRESS_SECOND_BYTE_PREFIX = 0x40

    private val mapper = ObjectMapper()
        .registerModule(
            KotlinModule.Builder()
                // When Jackson coerces an empty string to null for a Map/Collection field,
                // the Kotlin module would normally throw MissingKotlinParameterException
                // because the field is non-nullable. These two features tell it to substitute
                // an empty collection/map instead, honouring the Kotlin default values.
                .enable(KotlinFeature.NullToEmptyCollection)
                .enable(KotlinFeature.NullToEmptyMap)
                .build()
        )
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        // Treat empty strings as null for Maps and Collections so that a frontend
        // sending env="" or args="" degrades gracefully to the default empty value
        // rather than throwing InvalidFormatException.
        .apply {
            coercionConfigFor(LogicalType.Map)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull)
            coercionConfigFor(LogicalType.Collection)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull)
        }

    fun parse(config: JsonNode): McpServerConfig {
        val raw = mapper.treeToValue(config, McpServerConfig::class.java)
        return validate(raw)
    }

    private fun validate(config: McpServerConfig): McpServerConfig {
        val hasCommand = !config.command.isNullOrBlank()
        val hasUrl = !config.url.isNullOrBlank()

        require(hasCommand xor hasUrl) {
            when {
                !hasCommand && !hasUrl ->
                    "MCP integration config: exactly one of 'command' (stdio) or 'url' (HTTP) is required"
                else ->
                    "MCP integration config: 'command' and 'url' are mutually exclusive — set only one"
            }
        }

        if (hasCommand) validateStdio(config) else validateHttp(config)
        validateTimeouts(config)

        return config
    }

    private fun validateStdio(config: McpServerConfig) {
        config.args.forEachIndexed { i, arg ->
            require(arg.isNotBlank()) { "MCP integration config: args[$i] must not be blank" }
        }
    }

    private fun validateHttp(config: McpServerConfig) {
        val uri = validateUrl(config.url!!)
        if (config.authToken != null) {
            require(config.authToken.isNotBlank()) {
                "MCP integration config: 'authToken' must not be blank when present"
            }
            if (uri.scheme.equals("http", ignoreCase = true)) {
                logger.warn {
                    "MCP integration config: 'authToken' will be sent over cleartext http to '${uri.host}' " +
                        "— use https"
                }
            }
        }
    }

    private fun validateUrl(url: String): URI {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("MCP integration config: 'url' is not a valid URI (${e.reason})", e)
        }
        require(uri.isAbsolute) { "MCP integration config: 'url' must be an absolute http or https URL" }
        require(uri.scheme.lowercase() in SUPPORTED_URL_SCHEMES) {
            "MCP integration config: 'url' scheme must be http or https, got '${uri.scheme}'"
        }
        require(!hasUserInfo(uri)) { "MCP integration config: 'url' must not embed userinfo credentials" }
        val host = uri.host
        require(!host.isNullOrBlank()) { invalidHostMessage(uri) }
        require(!isLocalOrPrivateHost(host)) {
            "MCP integration config: 'url' host '$host' is local or private (localhost, loopback, link-local, " +
                "site-local, unique-local, shared address space or wildcard address); " +
                "only remote MCP servers are allowed"
        }
        return uri
    }

    /**
     * True when the authority carries userinfo. [URI.getRawUserInfo] alone is not enough: when the
     * host part is not a valid host name, [URI] keeps the whole authority raw and reports no userinfo.
     * Per RFC 3986 a literal `@` in the authority can only be the userinfo delimiter (the last one),
     * so any `@` means userinfo — including a password that itself contains an unencoded `@`.
     */
    private fun hasUserInfo(uri: URI): Boolean =
        uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true

    /**
     * Message for a url whose authority could not be parsed as a host. Only called once
     * [hasUserInfo] has ruled out any `@` in the authority, so echoing it cannot leak credentials.
     */
    private fun invalidHostMessage(uri: URI): String {
        val authority = uri.rawAuthority
        return if (authority.isNullOrBlank()) {
            "MCP integration config: 'url' must have a host"
        } else {
            "MCP integration config: 'url' must have a valid host name or IP literal ('$authority' is not one)"
        }
    }

    /**
     * True for `localhost` and literal IPs that are not routable to a remote server on the public
     * Internet: loopback, link-local, site-local (IPv4 private ranges), IPv6 unique-local, IPv4
     * shared address space and wildcard addresses. No DNS lookup. An IPv6 zone id (`fe80::1%eth0`,
     * percent-encoded as `%25` inside a URI) is dropped before classification so that a scoped
     * link-local literal cannot bypass the check.
     */
    private fun isLocalOrPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val literal = host.removePrefix("[").removeSuffix("]").substringBefore('%')
        val address = parseLiteralIp(literal) ?: return false
        return address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isAnyLocalAddress ||
            isUniqueLocal(address) ||
            isSharedAddressSpace(address)
    }

    /**
     * IPv6 unique-local addresses (`fc00::/7`, RFC 4193) are the IPv6 counterpart of IPv4 private
     * ranges but are not covered by [InetAddress.isSiteLocalAddress], which only knows the
     * deprecated `fec0::/10` block.
     */
    private fun isUniqueLocal(address: InetAddress): Boolean =
        address is Inet6Address && (address.address[0].toInt() and UNIQUE_LOCAL_PREFIX_MASK) == UNIQUE_LOCAL_PREFIX

    /**
     * IPv4 shared address space (`100.64.0.0/10`, RFC 6598) is assigned to carrier-grade NAT and is
     * not routable on the public Internet, yet [InetAddress] exposes no predicate for it.
     */
    private fun isSharedAddressSpace(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        return (bytes[0].toInt() and 0xFF) == SHARED_ADDRESS_FIRST_BYTE &&
            (bytes[1].toInt() and SHARED_ADDRESS_SECOND_BYTE_MASK) == SHARED_ADDRESS_SECOND_BYTE_PREFIX
    }

    private fun parseLiteralIp(literal: String): InetAddress? = try {
        InetAddress.ofLiteral(literal)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun validateTimeouts(config: McpServerConfig) {
        require(config.timeoutSeconds > 0) {
            "MCP integration config: 'timeoutSeconds' must be positive, got ${config.timeoutSeconds}"
        }
        require(config.toolCallTimeoutSeconds > 0) {
            "MCP integration config: 'toolCallTimeoutSeconds' must be positive, got ${config.toolCallTimeoutSeconds}"
        }
        require(config.idleTimeoutMinutes > 0) {
            "MCP integration config: 'idleTimeoutMinutes' must be positive, got ${config.idleTimeoutMinutes}"
        }
    }
}
