package io.whozoss.agentos.user

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.user.GroupsByExternalIdsRequest
import io.whozoss.agentos.sdk.api.user.UserApi
import io.whozoss.agentos.sdk.api.user.UserDto
import io.whozoss.agentos.sdk.api.userGroup.UserGroupSummary
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.userGroup.UserGroupService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
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
 * REST API for managing Users. Implements [UserApi] so external consumers can declare
 * a Feign client against the SDK interface.
 *
 * Standard CRUD operations are delegated to [crud] explicitly by name.
 * [UserApi] does not include a `listByParent` (users are root-level).
 */
@RestController
@RequestMapping(
    "/api/users",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserController(
    private val userService: UserService,
    permissionService: PermissionService,
    private val userGroupService: UserGroupService,
) : UserApi {
    private val crud =
        EntityCrudDelegate(
            service = userService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.USER,
            toResource = { toDto(it as User) },
            toDomain = { resource ->
                User(
                    metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
                    externalId = "", // server-managed
                    email = resource.email ?: "",
                    firstname = resource.firstname,
                    lastname = resource.lastname,
                    bio = resource.bio,
                    isAdmin = resource.isAdmin,
                )
            },
        )

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun listAll(): List<UserDto> = userService.findAll().map(::toDto)

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or #id.toString() == authentication.name")
    override fun getById(
        @PathVariable id: UUID,
    ): UserDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<UserDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun create(
        @Valid @RequestBody resource: UserDto,
    ): UserDto {
        val created = crud.create(resource)
        logger.info { "Super-admin created user ${created.id} (isAdmin=${created.isAdmin})" }
        return created
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or #id.toString() == authentication.name")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: UserDto,
    ): UserDto {
        val existing = userService.findByIds(listOf(id), false).firstOrNull()
            ?: throw ResourceNotFoundException("Entity not found: $id")
        val callerName = SecurityContextHolder.getContext().authentication?.name
        val isSelfEdit = callerName != null && id.toString() == callerName
        return userService
            .update(
                User(
                    metadata = existing.metadata,
                    externalId = existing.externalId,
                    email = resource.email ?: "",
                    firstname = resource.firstname,
                    lastname = resource.lastname,
                    bio = resource.bio,
                    isAdmin = if (isSelfEdit) existing.isAdmin else resource.isAdmin,
                ),
            ).let(::toDto)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the current user's profile")
    override fun getMe(): UserDto = toDto(userService.getCurrentUser())

    @PostMapping("/by-external-ids")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    override fun listByExternalIds(
        @RequestBody externalIds: List<String>,
    ): List<UserDto> {
        if (externalIds.isEmpty()) return emptyList()
        return userService.findByExternalIds(externalIds.toSet()).map(::toDto)
    }

    @PostMapping("/groups-by-external-ids")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    override fun getGroupsByExternalIds(
        @RequestBody request: GroupsByExternalIdsRequest,
    ): Map<String, List<UserGroupSummary>> {
        if (request.externalIds.isEmpty()) return emptyMap()
        val currentUser = userService.getCurrentUser()
        return userGroupService
            .findGroupsByUserExternalIdsVisibleToUser(request.externalIds, currentUser, request.namespaceId)
            .mapValues { (_, groups) ->
                groups.map { UserGroupSummary(id = it.id, name = it.name) }
            }
    }

    companion object : KLogging()
}

internal fun toDto(entity: User) =
    UserDto(
        id = entity.metadata.id,
        email = entity.email.ifBlank { null },
        externalId = entity.externalId,
        firstname = entity.firstname,
        lastname = entity.lastname,
        bio = entity.bio,
        isAdmin = entity.isAdmin,
    )
