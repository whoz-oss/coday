package io.whozoss.agentos.plugin

import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Discovers [UserContextProvider] implementations registered by PF4J plugins.
 *
 * Only the first registered provider is used. If no plugin provides an implementation,
 * [resolve] returns null and the scheduled-prompt executor skips context enrichment.
 *
 * Gated on `agentos.prompt.scheduler.enabled=true` so it is only active when the
 * scheduler is enabled (same condition as [ScheduledPromptExecutor]).
 */
@Component
@ConditionalOnProperty(name = ["agentos.prompt.scheduler.enabled"], havingValue = "true")
class UserContextProviderResolver(
    private val pluginManager: PluginManager,
) {
    companion object : KLogging()

    /**
     * Returns the first [UserContextProvider] found across all loaded plugins,
     * or null if none is registered.
     */
    fun resolve(): UserContextProvider? {
        val providers = pluginManager.getExtensions(UserContextProvider::class.java)
        if (providers.isEmpty()) {
            logger.debug { "[UserContextProviderResolver] No UserContextProvider found in loaded plugins" }
            return null
        }
        if (providers.size > 1) {
            logger.warn {
                "[UserContextProviderResolver] ${providers.size} providers found — using first: ${providers.first()::class.qualifiedName}"
            }
        }
        return providers.first()
    }
}
