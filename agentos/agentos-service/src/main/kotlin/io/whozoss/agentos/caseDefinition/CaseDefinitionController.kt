package io.whozoss.agentos.caseDefinition

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.api.caseDefinition.CaseDefinitionApi
import io.whozoss.agentos.sdk.api.caseDefinition.CaseDefinitionDto
import io.whozoss.agentos.sdk.api.caseDefinition.CaseDefinitionEffectiveRequest
import io.whozoss.agentos.sdk.api.caseDefinition.CaseDefinitionSearchRequest
import io.whozoss.agentos.sdk.api.caseDefinition.PlanningDto
import io.whozoss.agentos.sdk.api.caseDefinition.RecurrenceDto
import io.whozoss.agentos.sdk.api.caseDefinition.SchedulerEndType
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
 * REST API for [CaseDefinition] entities at /api/case-definitions.
 *
 * **Prompt lifecycle** — the controller owns prompt creation/update/deletion:
 * - POST: creates a generic Prompt named `{defName}-{agentName}`, then creates the CaseDefinition.
 * - PUT: updates the linked Prompt's content (and name if renamed), then updates the CaseDefinition.
 * - DELETE: deletes the CaseDefinition, then deletes the linked Prompt.
 *
 * **Cross-field validations** (applied on POST and PUT before service delegation):
 * - [planning.endDate] required when endType == ON_DATE, must be strictly after [planning.startDate].
 * - [planning.occurrenceCount] required and > 0 when endType == OCCURRENCES.
 */
@RestController
@RequestMapping(
    "/api/case-definitions",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CaseDefinitionController(
    private val caseDefinitionService: CaseDefinitionService,
    private val agentConfigService: AgentConfigService,
    private val promptService: PromptService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : CaseDefinitionApi {

    private val crud =
        EntityCrudDelegate(
            service = caseDefinitionService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.CASE_DEFINITION,
            toResource = { entity ->
                val def = entity as CaseDefinition
                val promptContent = promptService.findById(def.promptId)?.content?.firstOrNull() ?: ""
                toDto(def, promptContent)
            },
        )

    // -------------------------------------------------------------------------
    // Read endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'CaseDefinition', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): CaseDefinitionDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(@RequestBody request: SdkGetByIdsRequest): List<CaseDefinitionDto> =
        crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @Operation(summary = "List CaseDefinitions at an exact scope level")
    @PostMapping("/search", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun search(@Valid @RequestBody request: CaseDefinitionSearchRequest): List<CaseDefinitionDto> {
        val currentUser = userService.getCurrentUser()
        val resolvedNs = resolveOptionalNamespaceId(request.namespaceId, request.namespaceExternalId)
        if (request.userId != null && request.userId != currentUser.id && !currentUser.isAdmin) {
            throw AccessDeniedException("Cannot search case definitions for another user")
        }
        if (resolvedNs != null) {
            val granted = permissionService.hasPermission(
                userId = currentUser.id.toString(),
                entityType = EntityType.NAMESPACE,
                entityId = resolvedNs.toString(),
                action = Action.READ,
            )
            if (!granted) throw AccessDeniedException("Cannot read case definitions in namespace $resolvedNs")
        }
        return caseDefinitionService.findByScope(resolvedNs, request.userId, request.agentConfigIds)
            .map { def ->
                val promptContent = promptService.findById(def.promptId)?.content?.firstOrNull() ?: ""
                toDto(def, promptContent)
            }
    }

    @Operation(summary = "Effective case definitions for a user in a namespace")
    @PostMapping("/effective", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun effective(@Valid @RequestBody request: CaseDefinitionEffectiveRequest): List<CaseDefinitionDto> {
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
        if (!granted) throw AccessDeniedException("Cannot read case definitions in namespace $nsId")
        return caseDefinitionService.findEffective(nsId, currentUser.id)
            .let { defs ->
                if (request.agentConfigId != null) defs.filter { it.agentConfigId == request.agentConfigId } else defs
            }.map { def ->
                val promptContent = promptService.findById(def.promptId)?.content?.firstOrNull() ?: ""
                toDto(def, promptContent)
            }
    }

    // -------------------------------------------------------------------------
    // Write endpoints
    // -------------------------------------------------------------------------

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(@Valid @RequestBody resource: CaseDefinitionDto): CaseDefinitionDto {
        validatePlanning(resource.planning)

        val currentUser = userService.getCurrentUser()
        val me = currentUser.id
        if (resource.userId != null && resource.userId != me) {
            throw BadRequestException("userId in body must match authenticated user or be omitted")
        }

        val resolvedNs: UUID? = resource.namespaceId
        val resolvedUser: UUID? = if (resource.userId != null) me else null
        val isPlatform = resolvedNs == null && resolvedUser == null

        when {
            isPlatform -> if (!currentUser.isAdmin) throw AccessDeniedException("Platform-level CaseDefinition requires Super Admin")
            resolvedNs != null -> {
                val authzAction = if (resolvedUser != null) Action.READ else Action.WRITE
                val granted = permissionService.hasPermission(
                    userId = me.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = resolvedNs.toString(),
                    action = authzAction,
                )
                if (!granted) throw AccessDeniedException(
                    "Cannot create CaseDefinition in namespace $resolvedNs (${authzAction.name} required)",
                )
            }
        }

        if (resolvedNs != null && namespaceService.findById(resolvedNs) == null) {
            throw ResourceNotFoundException("Namespace not found: $resolvedNs")
        }

        val agentConfig = agentConfigService.findById(resource.agentConfigId)
            ?: throw ResourceNotFoundException("AgentConfig not found: ${resource.agentConfigId}")

        val prompt = promptService.create(
            Prompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = resolvedNs,
                userId = resolvedUser,
                agentConfigId = null,
                name = "${resource.name}-${agentConfig.name}",
                content = listOf(resource.promptContent),
            ),
        )

        val target = CaseDefinition(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resolvedNs,
            userId = resolvedUser,
            agentConfigId = resource.agentConfigId,
            promptId = prompt.id,
            name = resource.name,
            description = resource.description,
            recurrence = resource.recurrence.toDomain(),
            planning = resource.planning.toDomain(),
            enabled = resource.enabled,
        )
        return toDto(caseDefinitionService.create(target), resource.promptContent)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'CaseDefinition', 'WRITE')")
    @HideOnAccessDenied
    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: CaseDefinitionDto): CaseDefinitionDto {
        validatePlanning(resource.planning)
        val existing = caseDefinitionService.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")

        val existingPrompt = promptService.findById(existing.promptId)
            ?: throw ResourceNotFoundException("Prompt not found: ${existing.promptId}")
        val agentConfig = agentConfigService.findById(existing.agentConfigId)
            ?: throw ResourceNotFoundException("AgentConfig not found: ${existing.agentConfigId}")
        promptService.update(
            existingPrompt.copy(
                name = "${resource.name}-${agentConfig.name}",
                content = listOf(resource.promptContent),
            ),
        )

        return toDto(
            caseDefinitionService.update(toDomainForUpdate(resource, existing)),
            resource.promptContent,
        )
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'CaseDefinition', 'DELETE')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(@PathVariable id: UUID) {
        val existing = caseDefinitionService.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        crud.delete(id)
        promptService.delete(existing.promptId)
    }

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasPermission(#id, 'CaseDefinition', 'WRITE')")
    @HideOnAccessDenied
    @Operation(summary = "Toggle a case definition enabled/disabled")
    override fun toggle(@PathVariable id: UUID): CaseDefinitionDto {
        caseDefinitionService.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        val toggled = caseDefinitionService.toggle(id)
        val promptContent = promptService.findById(toggled.promptId)?.content?.firstOrNull() ?: ""
        return toDto(toggled, promptContent)
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private fun validatePlanning(planning: PlanningDto) {
        when (planning.endType) {
            SchedulerEndType.ON_DATE -> {
                val endDate = planning.endDate
                    ?: throw BadRequestException("endDate is required when endType is ON_DATE")
                if (!endDate.isAfter(planning.startDate)) {
                    throw BadRequestException("endDate must be after startDate")
                }
            }
            SchedulerEndType.OCCURRENCES -> {
                val count = planning.occurrenceCount
                    ?: throw BadRequestException("occurrenceCount is required when endType is OCCURRENCES")
                if (count <= 0) throw BadRequestException("occurrenceCount must be > 0")
            }
            SchedulerEndType.NEVER -> Unit
        }
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private fun toDomainForUpdate(resource: CaseDefinitionDto, existing: CaseDefinition): CaseDefinition =
        existing.copy(
            // Immutable: namespaceId, userId, agentConfigId, promptId
            name = resource.name,
            description = resource.description,
            recurrence = resource.recurrence.toDomain(),
            planning = resource.planning.toDomain(),
            enabled = resource.enabled,
        )

    // -------------------------------------------------------------------------
    // ExternalId resolution helpers
    // -------------------------------------------------------------------------

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
    every = every,
    unit = unit,
    days = days,
    timeUtc = timeUtc,  // LocalTime — no conversion needed
)

internal fun PlanningDto.toDomain(): Planning = Planning(
    startDate = startDate,
    endType = endType,
    endDate = endDate,
    occurrenceCount = occurrenceCount,
)

internal fun toDto(entity: CaseDefinition, promptContent: String): CaseDefinitionDto =
    CaseDefinitionDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        agentConfigId = entity.agentConfigId,
        promptContent = promptContent,
        name = entity.name,
        description = entity.description,
        recurrence = RecurrenceDto(
            every = entity.recurrence.every,
            unit = entity.recurrence.unit,
            days = entity.recurrence.days,
            timeUtc = entity.recurrence.timeUtc,  // LocalTime — no conversion needed
        ),
        planning = PlanningDto(
            startDate = entity.planning.startDate,
            endType = entity.planning.endType,
            endDate = entity.planning.endDate,
            occurrenceCount = entity.planning.occurrenceCount,
        ),
        enabled = entity.enabled,
        createdBy = entity.metadata.createdBy,
        createdOn = entity.metadata.created,
        updatedBy = entity.metadata.modifiedBy,
        updatedOn = entity.metadata.modified,
    )
