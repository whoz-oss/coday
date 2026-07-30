package io.whozoss.agentos.scheduledPrompt

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.OverlayScopeAuthorizer
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.RecurrenceDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptApi
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptEffectiveRequest
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptSearchRequest
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
import org.springframework.web.bind.annotation.PatchMapping
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
 * REST API for [ScheduledPrompt] entities at /api/scheduled-prompts.
 *
 * **Responsibilities**: HTTP routing, external-id resolution, DTO <-> domain mapping, and
 * delegating business logic to [ScheduledPromptService].
 *
 * **Authorisation is split by endpoint shape**:
 * - **Per-id endpoints** (`getById`, `getByIds`, `update`, `delete`, `enable`, `disable`) are
 *   fully declarative: `@PreAuthorize("hasPermission(#id, 'ScheduledPrompt', ...)")` delegates
 *   to [io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator], which resolves
 *   direct edges or the parent-Namespace edge. [effective] applies a separate, stricter
 *   DEPLOYED_TO check in [ScheduledPromptNodeNeo4jRepository.findEffective]: a namespace
 *   member without agent access is excluded from `effective` but not from `getByIds`.
 * - **Scope endpoints** (`search`, `effective`, `create`) have no target id to evaluate a
 *   permission against \u2014 the request body itself carries the `(namespaceId, userId)` scope
 *   that determines *which* permission check applies. This is handled by
 *   [OverlayScopeAuthorizer] rather than `@PreAuthorize`, for two structural reasons:
 *   1. **HTTP status codes are not uniform.** The mass-assignment guard on `body.userId`
 *      must produce 400 (malformed request), not 403 \u2014 a `@PreAuthorize` expression only
 *      ever throws `AccessDeniedException` (403) on `false`, it cannot distinguish the two.
 *   2. **External-id resolution would otherwise run twice.** The request body accepts a
 *      UUID or an external identifier, resolved via
 *      [io.whozoss.agentos.entity.ExternalIdentifierResolver] with a
 *      Neo4j lookup. SpEL evaluated ahead of the method body cannot see that resolved
 *      value, so it would have to re-resolve inside the method (an extra round-trip) or
 *      duplicate the resolution logic in SpEL (not expressible there).
 *   `@PreAuthorize("isAuthenticated()")` remains as the declarative floor on these three
 *   endpoints; [OverlayScopeAuthorizer] refines it once the request body is available.
 *
 * **Business logic** (prompt lifecycle, planning validation, namespace/agent existence
 * checks, prompt content resolution) is fully delegated to [ScheduledPromptService].
 */
@RestController
@RequestMapping(
    "/api/scheduled-prompts",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class ScheduledPromptController(
    private val scheduledPromptService: ScheduledPromptService,
    private val userService: UserService,
    private val permissionService: PermissionService,
    private val overlayScopeAuthorizer: OverlayScopeAuthorizer,
) : ScheduledPromptApi {

    private val crud =
        EntityCrudDelegate(
            service = scheduledPromptService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.SCHEDULED_PROMPT,
            toResource = { entity ->
                val scheduledPrompt = entity as ScheduledPrompt
                val (_, promptContent) = scheduledPromptService.findByIdWithContent(scheduledPrompt.id, withRemoved = true)
                    ?: (scheduledPrompt to "")
                toDto(scheduledPrompt, promptContent)
            },
        )

    // -------------------------------------------------------------------------
    // Read endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): ScheduledPromptDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(@RequestBody request: SdkGetByIdsRequest): List<ScheduledPromptDto> =
        crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @Operation(summary = "List ScheduledPrompts at an exact scope level")
    @PostMapping("/search", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun search(@Valid @RequestBody request: ScheduledPromptSearchRequest): List<ScheduledPromptDto> {
        val scope = overlayScopeAuthorizer.authorizeSearchOrThrow(
            pluralLabel = "scheduled prompts",
            namespaceId = request.namespaceId,
            namespaceExternalId = request.namespaceExternalId,
            userId = request.userId,
            userExternalId = request.userExternalId,
        )
        return scheduledPromptService
            .findByScope(scope.namespaceId, scope.userId, request.agentConfigIds)
            .let { scheduledPromptService.withContent(it) }
            .map { (sp, content) -> toDto(sp, content) }
    }

    @Operation(summary = "Effective scheduled prompts for the authenticated user in a namespace")
    @PostMapping("/effective", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun effective(@Valid @RequestBody request: ScheduledPromptEffectiveRequest): List<ScheduledPromptDto> {
        val scope = overlayScopeAuthorizer.authorizeEffectiveOrThrow(
            pluralLabel = "scheduled prompts",
            namespaceId = request.namespaceId,
            namespaceExternalId = request.namespaceExternalId,
        )
        return scheduledPromptService
            .findEffective(scope.namespaceId!!, scope.userId!!, request.agentConfigId)
            .let { scheduledPromptService.withContent(it) }
            .map { (sp, content) -> toDto(sp, content) }
    }

    // -------------------------------------------------------------------------
    // Write endpoints
    // -------------------------------------------------------------------------

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(@Valid @RequestBody resource: ScheduledPromptDto): ScheduledPromptDto {
        val scope = overlayScopeAuthorizer.authorizeCreateOrThrow(
            entityLabel = "ScheduledPrompt",
            requestedNamespaceId = resource.namespaceId,
            requestedUserId = resource.userId,
        )

        val entity = ScheduledPrompt(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = scope.namespaceId,
            userId = scope.userId,
            agentConfigId = resource.agentConfigId,
            promptTemplateId = UUID.randomUUID(), // placeholder — overwritten by createWithPrompt
            name = resource.name,
            description = resource.description,
            recurrence = resource.recurrence.toDomain(),
            planning = resource.planning.toDomain(),
            enabled = resource.enabled,
            nextRunAt = java.time.Instant.EPOCH,
        )
        val (saved, promptContent) = scheduledPromptService.createWithPrompt(entity, resource.promptContent)
        return toDto(saved, promptContent)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'WRITE')")
    @HideOnAccessDenied
    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: ScheduledPromptDto): ScheduledPromptDto {
        val existing = scheduledPromptService.findById(id)
            ?: throw ResourceNotFoundException("ScheduledPrompt not found: $id")

        val updated = toDomainForUpdate(resource, existing)
        val (saved, promptContent) = scheduledPromptService.updateWithPrompt(updated, resource.promptContent)
        return toDto(saved, promptContent)
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'DELETE')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(@PathVariable id: UUID) {
        scheduledPromptService.findById(id)
            ?: throw ResourceNotFoundException("ScheduledPrompt not found: $id")
        scheduledPromptService.deleteWithPrompt(id)
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'WRITE')")
    @HideOnAccessDenied
    @Operation(summary = "Enable a scheduled prompt (idempotent)")
    override fun enable(@PathVariable id: UUID): ScheduledPromptDto {
        scheduledPromptService.findById(id)
            ?: throw ResourceNotFoundException("ScheduledPrompt not found: $id")
        val enabled = scheduledPromptService.enable(id)
        val (sp, content) = scheduledPromptService.findByIdWithContent(enabled.id) ?: (enabled to "")
        return toDto(sp, content)
    }

    @PatchMapping("/{id}/disable")
    @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'WRITE')")
    @HideOnAccessDenied
    @Operation(summary = "Disable a scheduled prompt (idempotent)")
    override fun disable(@PathVariable id: UUID): ScheduledPromptDto {
        scheduledPromptService.findById(id)
            ?: throw ResourceNotFoundException("ScheduledPrompt not found: $id")
        val disabled = scheduledPromptService.disable(id)
        val (sp, content) = scheduledPromptService.findByIdWithContent(disabled.id) ?: (disabled to "")
        return toDto(sp, content)
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private fun toDomainForUpdate(resource: ScheduledPromptDto, existing: ScheduledPrompt): ScheduledPrompt =
        existing.copy(
            name = resource.name,
            description = resource.description,
            recurrence = resource.recurrence.toDomain(),
            planning = resource.planning.toDomain(),
            enabled = resource.enabled,
        )

    companion object : KLogging()
}

// ---------------------------------------------------------------------------
// DTO <-> Domain conversion extensions
// ---------------------------------------------------------------------------

internal fun RecurrenceDto.toDomain(): Recurrence = Recurrence(
    unit = unit,
    days = days,
    timeUtc = timeUtc,
)

internal fun PlanningDto.toDomain(): Planning = Planning(
    startDate = startDate,
    endType = endType,
    endDate = endDate,
    maxOccurrenceCount = maxOccurrenceCount,
)

internal fun toDto(entity: ScheduledPrompt, promptContent: String): ScheduledPromptDto =
    ScheduledPromptDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        agentConfigId = entity.agentConfigId,
        promptContent = promptContent,
        name = entity.name,
        description = entity.description,
        recurrence = RecurrenceDto(
            unit = entity.recurrence.unit,
            days = entity.recurrence.days,
            timeUtc = entity.recurrence.timeUtc,
        ),
        planning = PlanningDto(
            startDate = entity.planning.startDate,
            endType = entity.planning.endType,
            endDate = entity.planning.endDate,
            maxOccurrenceCount = entity.planning.maxOccurrenceCount,
        ),
        enabled = entity.enabled,
        nextRunAt = entity.nextRunAt,
        lastRunAt = entity.lastRunAt,
        createdBy = entity.metadata.createdBy,
        createdOn = entity.metadata.created,
        updatedBy = entity.metadata.modifiedBy,
        updatedOn = entity.metadata.modified,
    )
