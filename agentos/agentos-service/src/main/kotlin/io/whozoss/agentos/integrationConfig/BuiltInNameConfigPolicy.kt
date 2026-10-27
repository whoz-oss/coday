package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.exception.UnprocessableEntityException
import org.springframework.stereotype.Component

/**
 * Keeps the names of the built-in integrations (`AGENTOS_CONFIG`, `CASE_FILE_EXCHANGE`, …) for
 * themselves. An agent's `integrations` key selects a built-in integration and a configuration of
 * the same name alike, and tool names start with that name: a configuration named after a built-in
 * would be granted next to it and could offer tools under its names, such as the one that freezes
 * configuration plans.
 */
@Component
class BuiltInNameConfigPolicy(
    private val integrationTypeRegistry: IntegrationTypeRegistry,
) : IntegrationConfigPolicy {
    override fun supports(integrationType: String): Boolean = true

    override fun validate(config: IntegrationConfig) {
        if (integrationTypeRegistry.listTypes().any { it.builtIn && it.type.equals(config.name.trim(), ignoreCase = true) }) {
            throw UnprocessableEntityException("'${config.name}' is the name of a built-in integration: choose another name.")
        }
    }

    override fun afterSave(config: IntegrationConfig) = Unit
}
