package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.exception.ResourceNotFoundException
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST API exposing the catalogue of available integration types.
 *
 * These endpoints are read-only and return static descriptors. They do NOT manage
 * persisted [IntegrationConfig] entities (that is [IntegrationConfigController]'s job).
 *
 * Endpoints:
 *   GET /api/integration-types           - full catalogue, sorted by type
 *   GET /api/integration-types/{type}    - single descriptor by type identifier
 */
@RestController
@RequestMapping(
    "/api/integration-types",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationTypeController(
    private val integrationTypeRegistry: IntegrationTypeRegistry,
) {
    /**
     * List all known integration types with their configuration schemas.
     *
     * GET /api/integration-types
     */
    @GetMapping
    fun listTypes(): List<IntegrationTypeDescriptor> {
        logger.info { "Listing all integration types" }
        return integrationTypeRegistry.listTypes()
    }

    /**
     * Get a single integration type descriptor by its type identifier.
     *
     * GET /api/integration-types/{type}
     *
     * @param type  The integration type identifier (e.g. JIRA, GITHUB, SLACK). Case-sensitive.
     * @throws ResourceNotFoundException if no descriptor exists for [type].
     */
    @GetMapping("/{type}")
    fun getType(
        @PathVariable type: String,
    ): IntegrationTypeDescriptor {
        logger.info { "Getting integration type: $type" }
        return integrationTypeRegistry.findByType(type)
            ?: throw ResourceNotFoundException("Unknown integration type: $type")
    }

    companion object : KLogging()
}
