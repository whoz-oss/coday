package io.whozoss.agentos.plugin.filesystem

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
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

/**
 * The reverse of [substituteConfigPath]: puts [NAMESPACE_CONFIG_PATH_TOKEN] back wherever [configPath]
 * appears in a text node of [node], so a config rendered for its file carries the portable token
 * rather than this host's path.
 *
 * Only a whole path is replaced: an occurrence followed by a character that would continue its last
 * segment (`/repo/cfg` inside `/repo/cfg-old`) is left alone. A path that merely contains [configPath]
 * cannot be told apart from one built with the token, so both render the same way.
 */
fun tokenizeConfigPath(
    node: JsonNode?,
    configPath: String,
): JsonNode? {
    val occurrence = Regex(Regex.escape(configPath) + "(?![A-Za-z0-9._-])")
    fun walk(current: JsonNode?): JsonNode? =
        when (current) {
            is TextNode -> TextNode.valueOf(current.textValue().replace(occurrence) { NAMESPACE_CONFIG_PATH_TOKEN })
            is ObjectNode ->
                JsonNodeFactory.instance.objectNode().also { result ->
                    current.properties().forEach { (key, value) -> result.set<JsonNode>(key, walk(value)) }
                }
            is ArrayNode -> JsonNodeFactory.instance.arrayNode().also { result -> current.forEach { result.add(walk(it)) } }
            else -> current
        }
    return if (configPath.isEmpty()) node else walk(node)
}
