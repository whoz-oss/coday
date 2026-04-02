package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityController
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [IntegrationConfig] entities.
 *
 * Extends [EntityController] for standard CRUD:
 *   GET    /api/integration-configs/{id}
 *   POST   /api/integration-configs/by-ids
 *   GET    /api/integration-configs/by-parentId/{namespaceId}
 *   POST   /api/integration-configs
 *   PUT    /api/integration-configs/{id}
 *   DELETE /api/integration-configs/{id}
 *
 * Adds:
 *   GET    /api/integration-configs/by-namespace/{namespaceId} — explicit alias for by-parentId
 */
@RestController
@RequestMapping(
    "/api/integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationConfigController(
    private val integrationConfigService: IntegrationConfigService,
) : EntityController<IntegrationConfig, UUID>(integrationConfigService) {
    /**
     * List all integration configs for a given namespace.
     *
     * GET /api/integration-configs/by-namespace/{namespaceId}
     *
     * Alias for the inherited `by-parentId` endpoint, with a more explicit path.
     */
    @GetMapping("/by-namespace/{namespaceId}")
    fun listByNamespace(
        @PathVariable namespaceId: UUID,
    ): List<IntegrationConfig> {
        logger.info { "Listing integration configs for namespace $namespaceId" }
        return integrationConfigService.findByParent(namespaceId)
    }

    companion object : KLogging()
}
