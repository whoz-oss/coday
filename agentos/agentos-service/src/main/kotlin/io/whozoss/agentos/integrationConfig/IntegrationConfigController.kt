package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.auth.AuthorizationService
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [IntegrationConfig] entities.
 *
 * Extends [EntityController] with [IntegrationConfigResource] as the HTTP DTO.
 * All endpoints require ADMIN-level namespace access (integration configs are sensitive).
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/integration-configs/{id}
 *   POST   /api/integration-configs/by-ids
 *   GET    /api/integration-configs/by-parentId/{namespaceId}  — list by namespace
 *   POST   /api/integration-configs
 *   PUT    /api/integration-configs/{id}
 *   DELETE /api/integration-configs/{id}
 */
@RestController
@RequestMapping(
    "/api/integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationConfigController(
    private val integrationConfigService: IntegrationConfigService,
    private val authorizationService: AuthorizationService,
    private val userService: UserService,
) : EntityController<IntegrationConfig, UUID, IntegrationConfigResource>(integrationConfigService) {

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
    // Overridden CRUD with authorization
    // -------------------------------------------------------------------------

    override fun getById(@PathVariable id: UUID): IntegrationConfigResource {
        val entity = integrationConfigService.findById(id)
            ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        authorizationService.requireNamespaceAccess(
            currentUserId(),
            entity.namespaceId.toString(),
            NamespaceRole.ADMIN,
        )
        return toResource(entity)
    }

    override fun create(@Valid @RequestBody resource: IntegrationConfigResource): IntegrationConfigResource {
        authorizationService.requireNamespaceAccess(
            currentUserId(),
            resource.namespaceId.toString(),
            NamespaceRole.ADMIN,
        )
        return super.create(resource)
    }

    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: IntegrationConfigResource): IntegrationConfigResource {
        val existing = integrationConfigService.findById(id)
            ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        authorizationService.requireNamespaceAccess(
            currentUserId(),
            existing.namespaceId.toString(),
            NamespaceRole.ADMIN,
        )
        return super.update(id, resource)
    }

    override fun delete(@PathVariable id: UUID) {
        val existing = integrationConfigService.findById(id)
            ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        authorizationService.requireNamespaceAccess(
            currentUserId(),
            existing.namespaceId.toString(),
            NamespaceRole.ADMIN,
        )
        super.delete(id)
    }

    override fun listByParent(@PathVariable parentId: UUID): List<IntegrationConfigResource> {
        authorizationService.requireNamespaceAccess(
            currentUserId(),
            parentId.toString(),
            NamespaceRole.ADMIN,
        )
        return super.listByParent(parentId)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()

    companion object : KLogging()
}
