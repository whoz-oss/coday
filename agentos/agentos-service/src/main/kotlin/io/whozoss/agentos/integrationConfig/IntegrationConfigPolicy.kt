package io.whozoss.agentos.integrationConfig

/** Domain-owned validation and preparation for an integration configuration. */
interface IntegrationConfigPolicy {
    fun supports(integrationType: String): Boolean

    /** Validate before persistence, without network access or side effects. */
    fun validate(config: IntegrationConfig)

    /** Queue asynchronous preparation after a successful save, without performing remote work. */
    fun afterSave(config: IntegrationConfig)
}
