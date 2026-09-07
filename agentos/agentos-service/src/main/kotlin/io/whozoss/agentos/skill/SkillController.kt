package io.whozoss.agentos.skill

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.skill.SkillApi
import io.whozoss.agentos.sdk.api.skill.SkillDto
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
 * REST API for managing [Skill] entities at `/api/skills`.
 *
 * Implements [SkillApi] so external consumers can declare a Feign client against
 * the SDK interface.
 *
 * Authorization:
 * - READ: namespace MEMBER (transitive permission via `[:BELONGS_TO]`) or authenticated for platform skills
 * - WRITE/DELETE: namespace ADMIN or super-admin for platform skills
 * - CREATE: namespace ADMIN (target namespace from payload) or super-admin for platform skills
 *
 * Filesystem-backed skills are read-only through the API.
 */
@RestController
@RequestMapping(
    "/api/skills",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class SkillController(
    private val skillService: SkillService,
    userService: UserService,
    permissionService: PermissionService,
) : SkillApi {
    private val crud =
        EntityCrudDelegate(
            service = skillService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.SKILL,
            toResource = { toDto(it as Skill) },
            toDomain = { resource ->
                Skill(
                    metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
                    namespaceId = resource.namespaceId,
                    name = resource.name,
                    description = resource.description,
                    body = resource.body,
                )
            },
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Skill', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): SkillDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<SkillDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<SkillDto> = skillService.findByParent(parentId).map(::toDto)

    @GetMapping("/platform")
    @PreAuthorize("isAuthenticated()")
    override fun listPlatform(): List<SkillDto> = skillService.findPlatform().map(::toDto)

    @Operation(summary = "Create a Skill")
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: SkillDto,
    ): SkillDto = crud.create(resource)

    @Operation(summary = "Update a Skill")
    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Skill', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: SkillDto,
    ): SkillDto {
        val existing =
            skillService.findById(id)
                ?: throw ResourceNotFoundException("Skill not found: $id")
        return toDto(
            skillService.update(
                existing.copy(
                    name = resource.name,
                    description = resource.description,
                    body = resource.body,
                ),
            ),
        )
    }

    @Operation(summary = "Delete a Skill")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Skill', 'DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    companion object : KLogging()
}

internal fun toDto(entity: Skill) =
    SkillDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        name = entity.name,
        description = entity.description,
        body = entity.body,
        createdBy = entity.metadata.createdBy,
        createdOn = entity.metadata.created,
        updatedBy = entity.metadata.modifiedBy,
        updatedOn = entity.metadata.modified,
    )
