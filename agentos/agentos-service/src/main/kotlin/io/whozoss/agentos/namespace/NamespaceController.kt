package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.namespace.NamespaceApi
import io.whozoss.agentos.sdk.api.namespace.NamespaceDto
import io.whozoss.agentos.sdk.api.namespace.NamespaceListItem
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * REST API for managing Namespaces. Implements [NamespaceApi] so external consumers
 * can declare a Feign client against the SDK interface.
 *
 * Standard CRUD operations are delegated to [crud] explicitly by name.
 * [NamespaceApi] does not include a `listByParent` (namespaces are root-level).
 */
@RestController
@RequestMapping(
    "/api/namespaces",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class NamespaceController(
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : NamespaceApi {
    private val crud =
        EntityCrudDelegate<NamespaceDto>(
            service = namespaceService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.NAMESPACE,
            toResource = { (it as Namespace).toDto() },
            toDomain = { resource ->
                Namespace(
                    metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
                    name = resource.name,
                    description = resource.description,
                    configPath = resource.configPath?.takeIf { it.isNotBlank() },
                    externalId = resource.externalId?.takeIf { it.isNotBlank() },
                    defaultAgentName = resource.defaultAgentName?.takeIf { it.isNotBlank() },
                )
            },
        )

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    override fun listAll(): List<NamespaceListItem> {
        val currentUser = userService.getCurrentUser()
        if (currentUser.isAdmin) {
            return namespaceService
                .findAll()
                .filter { it.metadata.removed != true }
                .map { it.toListItem(SUPER_ADMIN) }
        }
        val userId = currentUser.id.toString()
        val readableIds = namespaceService.findIdsVisibleTo(userId, Action.READ)
        if (readableIds.isEmpty()) return emptyList()
        val namespaces = namespaceService.findByIds(readableIds)
        val adminIdsSet = namespaceService.findIdsVisibleTo(userId, Action.WRITE).toSet()
        return namespaces.map { ns ->
            ns.toListItem(if (ns.metadata.id in adminIdsSet) ADMIN else MEMBER)
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Namespace', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): NamespaceDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<NamespaceDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: NamespaceDto,
    ): NamespaceDto {
        val created = crud.create(resource)
        val namespaceId = created.id ?: error("Created namespace must have an id")
        val userId = userService.getCurrentUser().id.toString()
        runCatching {
            permissionService.grantPermission(
                userId,
                EntityType.NAMESPACE,
                namespaceId.toString(),
                PermissionRelation.ADMIN,
            )
        }.onFailure { e ->
            logger.warn(e) { "Auto-ADMIN grant failed for namespace $namespaceId — super-admin bypass still applies" }
        }
        return created
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Namespace', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: NamespaceDto,
    ): NamespaceDto {
        val existing = namespaceService.findById(id) ?: throw ResourceNotFoundException("Entity not found: $id")
        return namespaceService
            .update(
                existing.copy(
                    name = resource.name,
                    description = resource.description,
                    configPath = resource.configPath?.takeIf { it.isNotBlank() },
                    defaultAgentName = resource.defaultAgentName?.takeIf { it.isNotBlank() },
                    externalId = existing.externalId, // immutable post-create
                ),
            ).toDto()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun delete(
        @PathVariable id: UUID,
    ) {
        namespaceService.findById(id) ?: throw ResourceNotFoundException("Entity not found: $id")
        cascadeRevokeNamespacePermissions(id)
        crud.delete(id)
    }

    @PostMapping("/by-external-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    override fun listByExternalIds(
        @RequestBody externalIds: List<String>,
    ): List<NamespaceDto> {
        if (externalIds.isEmpty()) return emptyList()
        val found = namespaceService.findByExternalIds(externalIds)
        val currentUser = userService.getCurrentUser()
        return if (currentUser.isAdmin) {
            found
        } else {
            val readableIds = namespaceService.findIdsVisibleTo(currentUser.id.toString(), Action.READ).toSet()
            found.filter { it.metadata.id in readableIds }
        }.map { it.toDto() }
    }

    @PostMapping("/{namespaceId}/deploy-agents", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun deployAgents(
        @PathVariable namespaceId: UUID,
        @RequestBody request: NamespaceAgentDeployRequest,
    ) {
        namespaceService.deployAgents(namespaceId, request.agentIds)
    }

    @PostMapping("/{namespaceId}/undeploy-agents", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun undeployAgents(
        @PathVariable namespaceId: UUID,
        @RequestBody request: NamespaceAgentDeployRequest,
    ) {
        namespaceService.undeployAgents(namespaceId, request.agentIds)
    }

    private fun cascadeRevokeNamespacePermissions(namespaceId: UUID): Int {
        val nsStr = namespaceId.toString()
        val affectedUserIds = permissionService.listUsersWithPermission(EntityType.NAMESPACE, nsStr, null).distinct()
        var revoked = 0
        affectedUserIds.forEach { uid ->
            listOf(PermissionRelation.ADMIN, PermissionRelation.MEMBER).forEach { rel ->
                runCatching {
                    permissionService.revokePermission(uid, EntityType.NAMESPACE, nsStr, rel)
                    revoked++
                }.onFailure { e -> logger.warn(e) { "Failed to revoke $rel on namespace $namespaceId for user $uid" } }
            }
        }
        return revoked
    }

    companion object : KLogging() {
        private const val SUPER_ADMIN = "SUPER-ADMIN"
        private const val ADMIN = "ADMIN"
        private const val MEMBER = "MEMBER"
    }
}

// ---------------------------------------------------------------------------
// Extensions
// ---------------------------------------------------------------------------

fun Namespace.toDto() =
    NamespaceDto(
        id = metadata.id,
        name = name,
        description = description,
        configPath = configPath,
        externalId = externalId,
        defaultAgentName = defaultAgentName,
    )

private fun Namespace.toListItem(role: String) =
    NamespaceListItem(
        id = metadata.id,
        name = name,
        description = description,
        configPath = configPath?.takeIf { it.isNotBlank() },
        externalId = externalId,
        defaultAgentName = defaultAgentName,
        role = role,
    )
