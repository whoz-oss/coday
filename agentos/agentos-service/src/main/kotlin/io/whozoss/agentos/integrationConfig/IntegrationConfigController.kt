package io.whozoss.agentos.integrationConfig

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.entity.ScopeParams
import io.whozoss.agentos.entity.ScopedOwnershipCrudDelegate
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigApi
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for [IntegrationConfig] entities. Implements [IntegrationConfigApi] so
 * external consumers can declare a Feign client against the SDK interface.
 *
 * Standard CRUD operations are delegated to [crud] explicitly by name.
 */
@RestController
@RequestMapping(
    "/api/integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationConfigController(
    private val integrationConfigService: IntegrationConfigService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : IntegrationConfigApi {

    private val crud = ScopedOwnershipCrudDelegate<IntegrationConfigDto>(
        entityLabel = "IntegrationConfig",
        userService = userService,
        namespaceService = namespaceService,
        permissionService = permissionService,
        ownerOf = { (it as IntegrationConfig).userId },
        userIdOf = { it.userId },
        namespaceIdOf = { it.namespaceId },
        buildEntity = { resource, resolvedNs, resolvedUser ->
            IntegrationConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = resolvedNs,
                userId = resolvedUser,
                name = resource.name,
                integrationType = resource.integrationType,
                description = resource.description,
                parameters = resource.parameters,
            )
        },
        crud = EntityCrudDelegate(
            service = integrationConfigService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.INTEGRATION_CONFIG,
            toResource = { toDto(it as IntegrationConfig) },
            // toDomain omitted: ScopedOwnershipCrudDelegate always calls createEntity,
            // never crud.create(resource).
        ),
    )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): IntegrationConfigDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(@RequestBody request: SdkGetByIdsRequest): List<IntegrationConfigDto> =
        crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @Hidden
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("isAuthenticated()")
    override fun listByParent(@PathVariable parentId: UUID): List<IntegrationConfigDto> =
        throw ResourceNotFoundException("Endpoint removed; use GET /api/integration-configs?namespaceId=$parentId instead")

    @Operation(summary = "List IntegrationConfigs by scope")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    override fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
    ): List<IntegrationConfigDto> {
        val resolvedNs = ScopeParams.parseNamespaceParam(namespaceId)
        val me = userService.getCurrentUser().id
        ScopeParams.validateUserParam(userId)
        return integrationConfigService.findFiltered(
            namespaceId = resolvedNs,
            namespaceIsNone = namespaceId?.equals(ScopeParams.NONE, ignoreCase = true) == true,
            callerId = me,
            userRequested = userId != null,
            canReadNamespace = { nsId ->
                permissionService.hasPermission(me.toString(), EntityType.NAMESPACE, nsId.toString(), Action.READ)
            },
        ).map(::toDto)
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(@Valid @RequestBody resource: IntegrationConfigDto): IntegrationConfigDto =
        crud.create(resource)

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'WRITE')")
    @HideOnAccessDenied
    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: IntegrationConfigDto): IntegrationConfigDto {
        val existing = integrationConfigService.findById(id)
            ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        return integrationConfigService.update(
            existing.copy(
                name = resource.name,
                integrationType = existing.integrationType, // immutable post-create
                description = resource.description,
                parameters = resource.parameters,
            ),
        ).let(::toDto)
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'DELETE')")
    @HideOnAccessDenied
    override fun delete(@PathVariable id: UUID) = crud.delete(id)

    companion object : KLogging()
}

internal fun toDto(entity: IntegrationConfig) = IntegrationConfigDto(
    id = entity.metadata.id,
    namespaceId = entity.namespaceId,
    userId = entity.userId,
    name = entity.name,
    integrationType = entity.integrationType,
    description = entity.description,
    parameters = entity.parameters,
)
