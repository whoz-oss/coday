package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityController
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
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
 *   POST   /api/integration-configs/upsert  — create-or-update by (namespaceId, name)
 *   GET    /api/integration-configs/by-namespace/{namespaceId} — alias for by-parentId
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

    /**
     * Create or update an integration config by its natural key (namespaceId, name).
     *
     * POST /api/integration-configs/upsert
     *
     * If a config already exists for the (namespaceId, name) pair, it is updated.
     * Otherwise a new entity is created. This endpoint enforces the uniqueness constraint
     * without requiring the caller to know the entity's UUID.
     */
    @PostMapping(
        "/upsert",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ResponseStatus(HttpStatus.OK)
    fun upsert(
        @RequestBody config: IntegrationConfig,
    ): IntegrationConfig {
        logger.info { "Upserting integration config '${config.name}' for namespace ${config.namespaceId}" }
        return integrationConfigService.upsert(config)
    }

    companion object : KLogging()
}
