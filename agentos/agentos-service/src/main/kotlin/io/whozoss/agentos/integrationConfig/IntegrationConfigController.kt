package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
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
 * Authorization declared via `@PreAuthorize`:
 * - READ: namespace MEMBER (FR27)
 * - WRITE/DELETE: namespace ADMIN (FR23/24/25)
 * - CREATE: namespace ADMIN
 *
 * The `update` override preserves [IntegrationConfig.namespaceId] (mass-assignment guard).
 */
@RestController
@RequestMapping(
    "/api/integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationConfigController(
    private val integrationConfigService: IntegrationConfigService,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<IntegrationConfig, UUID, IntegrationConfigResource>(integrationConfigService, userService, permissionService) {

    override val entityType = EntityType.INTEGRATION_CONFIG

    override fun toResource(entity: IntegrationConfig): IntegrationConfigResource =
        IntegrationConfigResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            name = entity.name,
            integrationType = entity.integrationType,
            description = entity.description,
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
            description = resource.description,
            parameters = resource.parameters,
        )

    private fun toDomainForUpdate(
        resource: IntegrationConfigResource,
        existing: IntegrationConfig,
    ): IntegrationConfig =
        existing.copy(
            name = resource.name,
            integrationType = resource.integrationType,
            description = resource.description,
            parameters = resource.parameters,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): IntegrationConfigResource = super.getById(id)

    // POST /by-ids — inherited from EntityController.getByIds (story 5-4 factorisation).

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(@PathVariable parentId: UUID): List<IntegrationConfigResource> =
        super.listByParent(parentId)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    override fun create(@Valid @RequestBody resource: IntegrationConfigResource): IntegrationConfigResource =
        super.create(resource)

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: IntegrationConfigResource,
    ): IntegrationConfigResource {
        val existing = integrationConfigService.findById(id)
            ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        return toResource(integrationConfigService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'DELETE')")
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    companion object : KLogging()
}
