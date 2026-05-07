package io.whozoss.agentos.redirect

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for the REDIRECT internal integration.
 *
 * Declared in a dedicated `@Configuration` class rather than inside
 * [io.whozoss.agentos.agent.AgentServiceImpl] to break the circular dependency:
 *
 * ```
 * AgentServiceImpl
 *   → ToolRegistryService (via constructor)
 *     → springToolPlugins: List<ToolPlugin>
 *       → RedirectToolPlugin
 *         → agentResolver lambda → AgentConfigService
 * ```
 *
 * [AgentServiceImpl] itself depends on [ToolRegistryService], so a `@Bean` method
 * on [AgentServiceImpl] that produces a bean consumed by [ToolRegistryService] would
 * create a cycle. By moving the factory here — which depends only on [AgentConfigService],
 * not on [AgentServiceImpl] — the cycle is eliminated.
 */
@Configuration
class RedirectConfiguration(
    private val agentConfigService: AgentConfigService,
) {
    /**
     * Produces the [RedirectToolPlugin] bean with an [AgentConfigService]-backed
     * resolver lambda. The lambda resolves agents matching glob patterns at
     * [RedirectToolPlugin.provideTools] time, ensuring the list is always current.
     */
    @Bean
    fun redirectToolPlugin(): ToolPlugin =
        RedirectToolPlugin { namespaceId, patterns ->
            val regexes = patterns.map { globToRegex(it) }
            agentConfigService
                .findByParent(namespaceId)
                .filter { config -> regexes.any { it.matches(config.name) } }
        }

    companion object {
        /**
         * Converts a glob pattern (only `*` as wildcard) to a case-insensitive [Regex].
         * All regex metacharacters in the pattern are escaped before substituting `*`.
         */
        internal fun globToRegex(pattern: String): Regex {
            val escaped = pattern
                .replace("\\", "\\\\")
                .replace(".", "\\.")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("|", "\\|")
                .replace("?", ".")
                .replace("*", ".*")
            return Regex("^$escaped$", RegexOption.IGNORE_CASE)
        }
    }
}
