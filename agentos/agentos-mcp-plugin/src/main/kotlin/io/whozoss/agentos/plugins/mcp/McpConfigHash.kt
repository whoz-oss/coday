package io.whozoss.agentos.plugins.mcp

import java.security.MessageDigest

/**
 * Computes a deterministic hash for an [McpServerConfig].
 *
 * The hash covers only the fields that affect the MCP server's runtime behaviour:
 *
 * **stdio:** command, args (order-sensitive), env (order-independent, keys sorted), cwd.
 * **HTTP:** url, authToken.
 * **shared:** timeoutSeconds, toolCallTimeoutSeconds.
 *
 * [McpServerConfig.idleTimeoutMinutes] is excluded — it is pool eviction policy,
 * not server identity.
 *
 * Two configs with identical behaviour produce the same hash and will share
 * a pooled [McpConnection].
 */
fun McpServerConfig.configHash(): String {
    val sb = StringBuilder()
    when (transport) {
        McpTransport.STDIO -> {
            sb.append("stdio")
            sb.append('|').append(command)
            args.forEach { sb.append('|').append(it) }
            env.entries.sortedBy { it.key }.forEach { sb.append('|').append(it.key).append('=').append(it.value) }
            cwd?.let { sb.append('|').append(it) }
        }
        McpTransport.HTTP -> {
            sb.append("http")
            sb.append('|').append(url)
            authToken?.let { sb.append('|').append(it) }
        }
    }
    sb.append('|').append(timeoutSeconds)
    sb.append('|').append(toolCallTimeoutSeconds)
    val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
