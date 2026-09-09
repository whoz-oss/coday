package io.whozoss.agentos.sdk.api.integrationConfig

/**
 * HTTP API contract for the integration type catalogue.
 *
 * Implemented by `IntegrationTypeController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 */
interface IntegrationTypeApi {

    /** GET /api/integration-types — list all known integration types. */
    fun listTypes(): List<IntegrationTypeDescriptor>

    /** GET /api/integration-types/{type} — get a single descriptor by type identifier. */
    fun getType(type: String): IntegrationTypeDescriptor
}
