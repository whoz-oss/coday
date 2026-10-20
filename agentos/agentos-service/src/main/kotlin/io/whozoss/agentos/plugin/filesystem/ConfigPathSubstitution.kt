package io.whozoss.agentos.plugin.filesystem

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.util.substituteJsonTokens

/**
 * Token substituted, at filesystem-YAML parse time only, by the owning namespace's
 * `configPath`. Lets a committed `IntegrationConfig` YAML reference paths relative to
 * wherever the repo happens to be checked out, instead of hardcoding an absolute path
 * that is only valid on the machine that wrote it.
 *
 * Substitution is a plain textual replacement, positional and repeatable within a value
 * (e.g. `--config={{NAMESPACE_CONFIG_PATH}}/settings.json`) — never a prefix-only rule.
 * No path normalization is applied: `{{NAMESPACE_CONFIG_PATH}}/../scripts` resolves to
 * `<configPath>/../scripts` literally. Downstream consumers (`Path.of`, `File`,
 * `canonicalPath`, `BashConfigParser`'s own traversal check) resolve `..` themselves.
 *
 * This one is resolved **once, at load time, and only for filesystem configs**. The exchange
 * tokens of [io.whozoss.agentos.exchange.ExchangeIntegrationTypes] share the same spelling
 * convention but a different lifecycle: per agent run, for configs of every origin.
 */
const val NAMESPACE_CONFIG_PATH_TOKEN = "{{NAMESPACE_CONFIG_PATH}}"

/**
 * Replaces every occurrence of [NAMESPACE_CONFIG_PATH_TOKEN] in [text] with [configPath].
 *
 * One token, so a direct `replace` rather than [io.whozoss.agentos.util.substituteTokens], which
 * would fold over a one-entry map to reach the same call. This runs per text node of every
 * filesystem integration and agent config at load time. Only the recursive JSON walk is worth
 * sharing, and that is [substituteJsonTokens].
 */
fun substituteConfigPath(
    text: String,
    configPath: String,
): String = text.replace(NAMESPACE_CONFIG_PATH_TOKEN, configPath)

/**
 * Returns a new [JsonNode] with [NAMESPACE_CONFIG_PATH_TOKEN] substituted by [configPath]
 * in every text node, recursively through objects and arrays. Numbers, booleans and nulls
 * are preserved as-is. The input [node] is never mutated — a fresh tree is built so the
 * cached original stays safe to share.
 *
 * Returns [node] unchanged when either [node] or [configPath] is null.
 */
fun substituteConfigPath(
    node: JsonNode?,
    configPath: String?,
): JsonNode? =
    when (configPath) {
        null -> node
        else -> substituteJsonTokens(node, mapOf(NAMESPACE_CONFIG_PATH_TOKEN to configPath))
    }
