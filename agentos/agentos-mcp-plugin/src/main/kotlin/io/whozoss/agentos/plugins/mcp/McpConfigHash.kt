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
private const val HASH_SEPARATOR = "|"
private const val HASH_KEY_VALUE_SEPARATOR = "="
private const val HASH_ALGORITHM = "SHA-256"

fun McpServerConfig.configHash(): String {
    val sb = StringBuilder()
    when (transport) {
        McpTransport.STDIO -> {
            sb.append("stdio")
            sb.append(HASH_SEPARATOR).append(command)
            args.forEach { sb.append(HASH_SEPARATOR).append(it) }
            env.entries.sortedBy { it.key }.forEach {
                sb.append(HASH_SEPARATOR).append(it.key).append(HASH_KEY_VALUE_SEPARATOR).append(it.value)
            }
            cwd?.let { sb.append(HASH_SEPARATOR).append(it) }
        }
        McpTransport.HTTP -> {
            sb.append("http")
            sb.append(HASH_SEPARATOR).append(url)
            authToken?.let { sb.append(HASH_SEPARATOR).append(it) }
        }
    }
    sb.append(HASH_SEPARATOR).append(timeoutSeconds)
    sb.append(HASH_SEPARATOR).append(toolCallTimeoutSeconds)
    val digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(sb.toString().toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
