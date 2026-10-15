package io.whozoss.agentos.integrationConfig

/** Domain-owned validation and preparation for an integration configuration. */
interface IntegrationConfigPolicy {
    fun supports(integrationType: String): Boolean

    /** Coordinate validation, persistence and preparation intent with other domain operations. */
    fun <T> aroundSave(config: IntegrationConfig, action: () -> T): T = action()

    /** Validate before persistence; provisioning belongs to the background worker. */
    fun validate(config: IntegrationConfig)

    /** Queue asynchronous preparation after a successful save, without performing remote work. */
    fun afterSave(config: IntegrationConfig)
}
