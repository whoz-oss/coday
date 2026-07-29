package io.whozoss.agentos.scheduledPrompt

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
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
import org.springframework.security.access.AccessDeniedException
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
 * **Responsibilities**: HTTP routing, authentication/authorisation (scope dispatch,
 * permission checks), external-id resolution, and DTO ↔ domain mapping.
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
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : ScheduledPromptApi {

    private val crud =
        EntityCrudDelegate(
            service = scheduledPromptService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.SCHEDULED_PROMPT,
            toResource = { entity ->
                val sp = entity as ScheduledPrompt
                val (_, promptContent) = scheduledPromptService.findByIdWithContent(sp.id, withRemoved = true)
                    ?: (sp to "")
                toDto(sp, promptContent)
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
        val currentUser = userService.getCurrentUser()
        val resolvedNs = resolveOptionalNamespaceId(request.namespaceId, request.namespaceExternalId)
        if (request.userId != null && request.userId != currentUser.id && !currentUser.isAdmin) {
            throw AccessDeniedException("Cannot search scheduled prompts for another user")
        }
        if (resolvedNs != null) {
            val granted = permissionService.hasPermission(
                userId = currentUser.id.toString(),
                entityType = EntityType.NAMESPACE,
                entityId = resolvedNs.toString(),
                action = Action.READ,
            )
            if (!granted) throw AccessDeniedException("Cannot read scheduled prompts in namespace $resolvedNs")
        }
        return scheduledPromptService
            .findByScope(resolvedNs, request.userId, request.agentConfigIds)
            .let { scheduledPromptService.withContent(it) }
            .map { (sp, content) -> toDto(sp, content) }
    }

    @Operation(summary = "Effective scheduled prompts for a user in a namespace")
    @PostMapping("/effective", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun effective(@Valid @RequestBody request: ScheduledPromptEffectiveRequest): List<ScheduledPromptDto> {
        val nsId = resolveNamespaceId(request.namespaceId, request.namespaceExternalId)
        val uId = resolveUserId(request.userId, request.userExternalId)
        val currentUser = userService.getCurrentUser()
        if (uId != currentUser.id) throw BadRequestException("userId must match authenticated user")
        val granted = permissionService.hasPermission(
            userId = currentUser.id.toString(),
            entityType = EntityType.NAMESPACE,
            entityId = nsId.toString(),
            action = Action.READ,
        )
        if (!granted) throw AccessDeniedException("Cannot read scheduled prompts in namespace $nsId")
        return scheduledPromptService
            .findEffective(nsId, currentUser.id)
            .filter { request.agentConfigId == null || it.agentConfigId == request.agentConfigId }
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
        val currentUser = userService.getCurrentUser()
        val me = currentUser.id
        if (resource.userId != null && resource.userId != me) {
            throw BadRequestException("userId in body must match authenticated user or be omitted")
        }

        val resolvedNs: UUID? = resource.namespaceId
        val resolvedUser: UUID? = if (resource.userId != null) me else null
        val isPlatform = resolvedNs == null && resolvedUser == null

        when {
            isPlatform -> if (!currentUser.isAdmin) throw AccessDeniedException("Platform-level ScheduledPrompt requires Super Admin")
            resolvedNs != null -> {
                val authzAction = if (resolvedUser != null) Action.READ else Action.WRITE
                val granted = permissionService.hasPermission(
                    userId = me.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = resolvedNs.toString(),
                    action = authzAction,
                )
                if (!granted) throw AccessDeniedException(
                    "Cannot create ScheduledPrompt in namespace $resolvedNs (${authzAction.name} required)",
                )
            }
        }

        val entity = ScheduledPrompt(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resolvedNs,
            userId = resolvedUser,
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

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'WRITE')")
    @HideOnAccessDenied
    @Operation(summary = "Toggle a scheduled prompt enabled/disabled")
    override fun toggle(@PathVariable id: UUID): ScheduledPromptDto {
        scheduledPromptService.findById(id)
            ?: throw ResourceNotFoundException("ScheduledPrompt not found: $id")
        val toggled = scheduledPromptService.toggle(id)
        val (sp, content) = scheduledPromptService.findByIdWithContent(toggled.id) ?: (toggled to "")
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

    private fun resolveNamespaceId(id: UUID?, externalId: String?): UUID {
        if (id != null && externalId != null) throw BadRequestException("Provide namespaceId or namespaceExternalId, not both")
        return id
            ?: externalId?.let {
                namespaceService.findByExternalId(it)?.metadata?.id
                    ?: throw ResourceNotFoundException("Namespace not found for externalId: $it")
            }
            ?: throw BadRequestException("namespaceId or namespaceExternalId is required")
    }

    private fun resolveOptionalNamespaceId(id: UUID?, externalId: String?): UUID? {
        if (id != null && externalId != null) throw BadRequestException("Provide namespaceId or namespaceExternalId, not both")
        return id ?: externalId?.let {
            namespaceService.findByExternalId(it)?.metadata?.id
                ?: throw ResourceNotFoundException("Namespace not found for externalId: $it")
        }
    }

    private fun resolveUserId(id: UUID?, externalId: String?): UUID {
        if (id != null && externalId != null) throw BadRequestException("Provide userId or userExternalId, not both")
        return id
            ?: externalId?.let {
                userService.findByExternalId(it)?.metadata?.id
                    ?: throw ResourceNotFoundException("User not found for externalId: $it")
            }
            ?: throw BadRequestException("userId or userExternalId is required")
    }

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
