package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Primary implementation of [IntegrationTypeRegistry].
 *
 * Descriptors are contributed at runtime via [registerFromPlugin] as each [ToolPlugin] is
 * loaded by [io.whozoss.agentos.tool.ToolRegistryService].
 *
 * Thread-safety: [ConcurrentHashMap] is used so that plugin registration (which happens
 * during @PostConstruct) is safe even if a read arrives concurrently.
 */
@Component
class CompositeIntegrationTypeRegistry : IntegrationTypeRegistry {

    /** Descriptors contributed by loaded plugins, keyed by integrationType. */
    private val pluginDescriptors = ConcurrentHashMap<String, IntegrationTypeDescriptor>()

    override fun listTypes(): List<IntegrationTypeDescriptor> =
        pluginDescriptors.values.sortedBy { it.type }

    override fun findByType(type: String): IntegrationTypeDescriptor? =
        pluginDescriptors[type]

    override fun registerFromPlugin(plugin: ToolPlugin) {
        val schema = plugin.configSchema ?: run {
            logger.debug { "Plugin '${plugin.integrationType}' has no configSchema -- skipping descriptor registration" }
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
