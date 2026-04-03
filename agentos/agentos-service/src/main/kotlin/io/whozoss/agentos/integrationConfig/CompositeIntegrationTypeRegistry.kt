package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Primary implementation of [IntegrationTypeRegistry].
 *
 * Merges two sources of [IntegrationTypeDescriptor]s:
 * 1. **Plugin-contributed** — registered at runtime via [registerFromPlugin] as each
 *    [ToolPlugin] is loaded. A plugin descriptor always takes precedence over a hardcoded one
 *    for the same [IntegrationTypeDescriptor.type].
 * 2. **Hardcoded fallback** — the static descriptors in [HardcodedIntegrationTypeRegistry]
 *    cover integration types that have no plugin yet (JIRA, GITHUB, SLACK).
 *
 * Thread-safety: [ConcurrentHashMap] is used so that plugin registration (which happens
 * during [io.whozoss.agentos.tool.ToolRegistryService]'s @PostConstruct) is safe even if
 * a read arrives concurrently.
 */
@Component
class CompositeIntegrationTypeRegistry(
    private val hardcoded: HardcodedIntegrationTypeRegistry,
    private val objectMapper: ObjectMapper,
) : IntegrationTypeRegistry {

    /** Descriptors contributed by loaded plugins, keyed by integrationType. */
    private val pluginDescriptors = ConcurrentHashMap<String, IntegrationTypeDescriptor>()

    override fun listTypes(): List<IntegrationTypeDescriptor> {
        val merged = mutableMapOf<String, IntegrationTypeDescriptor>()
        // Start with hardcoded fallbacks
        hardcoded.listTypes().forEach { merged[it.type] = it }
        // Plugin-contributed entries override hardcoded ones for the same type
        pluginDescriptors.forEach { (type, descriptor) -> merged[type] = descriptor }
        return merged.values.sortedBy { it.type }
    }

    override fun findByType(type: String): IntegrationTypeDescriptor? =
        pluginDescriptors[type] ?: hardcoded.findByType(type)

    override fun registerFromPlugin(plugin: ToolPlugin) {
        val schema = plugin.configSchema ?: run {
            logger.debug { "Plugin '${plugin.integrationType}' has no configSchema — skipping descriptor registration" }
            return
        }
        val descriptor = IntegrationTypeDescriptor(
            type = plugin.integrationType,
            displayName = plugin.integrationType
                .lowercase()
                .replaceFirstChar { it.uppercase() }
                .replace('_', ' '),
            description = "Configuration for the ${plugin.integrationType} integration.",
            configSchema = schema,
        )
        val previous = pluginDescriptors.put(plugin.integrationType, descriptor)
        if (previous != null) {
            logger.warn {
                "Duplicate integrationType '${plugin.integrationType}': " +
                    "previous plugin descriptor replaced by new one"
            }
        } else {
            logger.info { "Registered integration type descriptor from plugin: '${plugin.integrationType}'" }
        }
    }

    companion object : KLogging()
}
