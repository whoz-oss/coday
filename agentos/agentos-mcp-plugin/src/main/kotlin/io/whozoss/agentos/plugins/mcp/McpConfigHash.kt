package io.whozoss.agentos.plugins.mcp

import java.security.MessageDigest

/**
 * Computes a deterministic hash for an [McpServerConfig].
 *
 * The hash covers only the fields that affect the MCP server's runtime behaviour:
 * command, args (order-sensitive), env (order-independent, keys sorted),
 * cwd, and timeouts.
 *
 * Two configs with identical behaviour produce the same hash and will share
 * a pooled [McpConnection].
 */
fun McpServerConfig.configHash(): String {
    val sb = StringBuilder()
    sb.append(command)
    args.forEach { sb.append('|').append(it) }
    env.entries.sortedBy { it.key }.forEach { sb.append('|').append(it.key).append('=').append(it.value) }
    cwd?.let { sb.append('|').append(it) }
    sb.append('|').append(timeoutSeconds)
    sb.append('|').append(toolCallTimeoutSeconds)
    val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
