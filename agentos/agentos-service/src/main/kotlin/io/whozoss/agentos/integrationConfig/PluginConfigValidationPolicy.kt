package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.tool.ToolRegistryService
import mu.KLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Validates, at save time, the parameters of the integration types whose plugin owns a strict configuration
 * policy, by asking the plugin itself to build its tools from the saved parameters.
 *
 * ## Why delegate to the plugin
 *
 * The plugin is the only owner of its configuration rules (allow/block overlap, bare IDs, limits...).
 * Restating them here, or in the UI, would create a second copy that drifts. A plugin listed in
 * [VALIDATING_TYPES] refuses an invalid configuration by throwing from `provideTools`, with a message that
 * names a rule and a field path but never a configuration value; that message is surfaced as a 422, which the
 * integration form displays, so the mistake is refused where it is made instead of at the next agent run.
 *
 * ## Why an explicit list of types
 *
 * Calling `provideTools` is only safe for a plugin whose tool construction is pure: no network, no process, no
 * credential. That is a property of the plugin, not of the SDK contract (an MCP plugin connects in
 * `provideTools`), so it is opted in per type rather than inferred.
 *
 * ## Limits
 *
 * Each saved row is validated alone. Layers are merged by name at run time, so a namespace row that is valid
 * on its own can still produce an invalid merged policy with the platform row (e.g. block an ID the platform
 * allows). The run then reports the integration as failed to load, with the same message in the logs.
 *
 * [toolRegistry] is resolved lazily: the registry depends on the Spring tool plugins, and this policy sits
 * under the integration config service, so an eager dependency could close a cycle.
 */
@Component
class PluginConfigValidationPolicy(
    private val toolRegistry: ObjectProvider<ToolRegistryService>,
) : IntegrationConfigPolicy {
    override fun supports(integrationType: String): Boolean = integrationType in VALIDATING_TYPES

    override fun validate(config: IntegrationConfig) {
        val plugin = toolRegistry.getIfAvailable()?.findPlugin(config.integrationType)
        if (plugin == null) {
            // Nothing to validate against; the run will report the missing plugin as a load failure.
            logger.warn { "No plugin loaded for '${config.integrationType}': configuration '${config.name}' saved unvalidated" }
            return
        }
        try {
            plugin.provideTools(config.parameters, config.name, null)
        } catch (e: IllegalArgumentException) {
            // The validating plugins guarantee their refusal messages carry no configuration value.
            throw UnprocessableEntityException(e.message ?: "Invalid ${config.integrationType} configuration")
        } catch (e: Exception) {
            logger.warn { "Unexpected ${e::class.simpleName} while validating '${config.name}' (${config.integrationType})" }
            throw UnprocessableEntityException("Invalid ${config.integrationType} configuration")
        }
    }

    override fun afterSave(config: IntegrationConfig) {
        // No-op: validation only.
    }

    companion object : KLogging() {
        /** Types whose `provideTools` is pure and refuses invalid parameters with a value-free message. */
        val VALIDATING_TYPES: Set<String> = setOf("GOOGLE_WORKSPACE")
    }
}
