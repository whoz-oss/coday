package io.whozoss.agentos.integrationConfig

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [IntegrationConfig] entities.
 *
 * Extends [EntityController] with [IntegrationConfigResource] as the HTTP DTO.
 *
 * Standard CRUD endpoints (inherited):
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
) : EntityController<IntegrationConfig, UUID, IntegrationConfigResource>(integrationConfigService) {

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: IntegrationConfig): IntegrationConfigResource =
        IntegrationConfigResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            name = entity.name,
            integrationType = entity.integrationType,
            parameters = entity.parameters,
        )

    override fun toDomain(resource: IntegrationConfigResource): IntegrationConfig =
        IntegrationConfig(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            // namespaceId is guaranteed non-null by @NotNull on IntegrationConfigResource;
            // !! is safe here — @Valid prevents toDomain from being called with a null value.
            namespaceId = resource.namespaceId!!,
            name = resource.name,
            integrationType = resource.integrationType,
            parameters = resource.parameters,
        )

    // -------------------------------------------------------------------------
    // operationId overrides — keep OpenAPI names stable across regenerations
    // -------------------------------------------------------------------------

    @Operation(operationId = "getIntegrationConfigById")
    override fun getById(id: UUID) = super.getById(id)

    @Operation(operationId = "getIntegrationConfigsByIds")
    override fun getByIds(ids: List<UUID>) = super.getByIds(ids)

    @Operation(operationId = "listIntegrationConfigsByParent")
    override fun listByParent(parentId: UUID) = super.listByParent(parentId)

    @Operation(operationId = "createIntegrationConfig")
    override fun create(@Valid @RequestBody resource: IntegrationConfigResource) = super.create(resource)

    @Operation(operationId = "updateIntegrationConfig")
    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: IntegrationConfigResource) = super.update(id, resource)

    @Operation(operationId = "deleteIntegrationConfig")
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    // -------------------------------------------------------------------------
    // Additional endpoints
    // -------------------------------------------------------------------------

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
    ): List<IntegrationConfigResource> {
        logger.info { "Listing integration configs for namespace $namespaceId" }
        return integrationConfigService.findByParent(namespaceId).map { toResource(it) }
    }

    companion object : KLogging()
}
