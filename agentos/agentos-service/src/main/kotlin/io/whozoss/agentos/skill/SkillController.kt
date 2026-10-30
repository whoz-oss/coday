package io.whozoss.agentos.skill

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.skill.SkillApi
import io.whozoss.agentos.sdk.api.skill.SkillDto
import io.whozoss.agentos.sdk.api.skill.SkillSummaryDto
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
    private val permissionService: PermissionService,
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
                    resources = resource.resources ?: emptyMap(),
                )
            },
        )

    /**
     * Filesystem-backed skills have no Neo4j node, so `hasPermission(#id, 'Skill', 'READ')`
     * alone always fails for them — the SpEL falls through to [SkillAuthorizationService.canRead],
     * which resolves the skill (persisted or filesystem-backed) via [SkillService] and
     * authorizes against its owning namespace.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Skill', 'READ') or @skillAuthorizationService.canRead(authentication.name, #id)")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): SkillDto = crud.getById(id)

    /**
     * [extraVisibility] grants visibility for filesystem-backed skills, which
     * [PermissionService.filterVisibleIds] cannot see (no Neo4j node): a skill is
     * visible when its owning namespace grants the caller READ, or when it is a
     * platform skill (`namespaceId == null`).
     */
    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<SkillDto> = crud.getByIds(
        GetByIdsRequest(request.ids, request.withRemoved),
        extraVisibility = { entity, callerId ->
            val skill = entity as Skill
            val namespaceId = skill.namespaceId
            namespaceId == null ||
                permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        },
    )

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<SkillDto> = skillService.findByParent(parentId).map(::toDto)

    @GetMapping("/platform")
    @PreAuthorize("isAuthenticated()")
    override fun listPlatform(): List<SkillDto> = skillService.findPlatform().map(::toDto)

    /**
     * Summary listing of the skills a `*` selector resolves in [namespaceId] — the authoritative
     * candidate set for [io.whozoss.agentos.agentConfig.AgentConfig.skillSelectors].
     *
     * Deliberately NOT part of [SkillApi]: adding a method to that interface is a source-breaking
     * change for external Feign implementors, and this endpoint serves the AgentOS UI rather than
     * the entity CRUD contract. Same precedent as `AgentConfigController.listPlatformAgents`.
     *
     * It delegates to [SkillService.findSkills], i.e. the very function the agent runtime uses, so
     * a name offered here can never resolve differently at run time. Contrast with composing
     * [listByParent] + [listPlatform] client-side, which would require re-implementing
     * namespace-over-platform shadowing outside the server and would ship every skill's body and
     * resources to populate a picker.
     */
    @Operation(
        summary = "Effective skills for a namespace",
        description = "Returns the skill catalogue a '*' selector resolves in this namespace: " +
            "persisted and filesystem namespace skills merged with platform skills, namespace skills " +
            "shadowing platform skills of the same name (case-insensitive). Summary projection — no " +
            "body, no resources.",
    )
    @GetMapping("/effective")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    suspend fun listEffective(
        @RequestParam namespaceId: UUID,
    ): List<SkillSummaryDto> = skillService.findSkills(namespaceId, listOf(ALL_SKILLS_SELECTOR)).map(::toSummaryDto)

    @Operation(summary = "Create a Skill")
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: SkillDto,
    ): SkillDto = crud.create(resource)

    /**
     * The `canWrite` fallback exists so that a filesystem-backed skill — which has no Neo4j node,
     * so `hasPermission(#id, 'Skill', 'WRITE')` can never succeed — reaches
     * [SkillService.update]'s informative 400 instead of being masked as a 404 by
     * [HideOnAccessDenied]. It authorizes filesystem-backed skills only, so persisted skills keep
     * exactly the authorization they had before.
     */
    @Operation(summary = "Update a Skill")
    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Skill', 'WRITE') or @skillAuthorizationService.canWrite(authentication.name, #id)")
    @HideOnAccessDenied
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
                    resources = resource.resources ?: existing.resources,
                ),
            ),
        )
    }

    /** Same `canDelete` fallback rationale as [update]. */
    @Operation(summary = "Delete a Skill")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Skill', 'DELETE') or @skillAuthorizationService.canDelete(authentication.name, #id)")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    companion object : KLogging() {
        /** The wildcard selector understood by [SkillService.findSkills]. */
        private const val ALL_SKILLS_SELECTOR = "*"
    }
}

internal fun toDto(entity: Skill) =
    SkillDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        name = entity.name,
        description = entity.description,
        body = entity.body,
        resources = entity.resources.takeIf { it.isNotEmpty() },
        createdBy = entity.metadata.createdBy,
        createdOn = entity.metadata.created,
        updatedBy = entity.metadata.modifiedBy,
        updatedOn = entity.metadata.modified,
        readOnly = entity.isFilesystemBacked,
    )

/**
 * Maps a [Skill] to its compact projection. Single mapping site, shared by
 * [SkillController.listEffective] and
 * [io.whozoss.agentos.agentConfig.AgentConfigController.getDefinition].
 */
internal fun toSummaryDto(entity: Skill) =
    SkillSummaryDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        name = entity.name,
        description = entity.description,
        readOnly = entity.isFilesystemBacked,
    )
