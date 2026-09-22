package io.whozoss.agentos.integrationConfig

/**
 * Type-specific validation applied before an [IntegrationConfig] is persisted.
 *
 * The generic CRUD only knows that `parameters` is JSON: it cannot tell a usable repository URL
 * from `ssh://…`, nor an auth-setting UUID from a name. Without a hook here those mistakes are
 * accepted with a 201 and only surface much later, when something tries to *use* the
 * configuration — which is exactly the deferred failure the API edge exists to prevent.
 *
 * Implementations live with their domain (the Git validator sits in `io.whozoss.agentos.git`) and
 * are collected by Spring, so adding a type never means touching the generic service.
 *
 * Validation must be **pure**: no network, no side effect. It runs inside a create/update request.
 */
interface IntegrationConfigValidator {
    /** Whether this validator owns [integrationType]. Case-insensitive by convention. */
    fun supports(integrationType: String): Boolean

    /**
     * @throws io.whozoss.agentos.exception.BadRequestException when the configuration is not usable.
     */
    fun validate(config: IntegrationConfig)
}
