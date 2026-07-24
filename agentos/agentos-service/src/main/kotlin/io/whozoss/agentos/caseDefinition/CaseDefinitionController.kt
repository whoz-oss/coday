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
import io.whozoss.agentos.sdk.api.caseDefinition.CaseDefinitionScheduleFrequency
import io.whozoss.agentos.sdk.api.caseDefinition.CaseDefinitionSearchRequest
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
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * REST API for [CaseDefinition] entities at /api/case-definitions.
 *
 * Implements [CaseDefinitionApi] so external consumers can declare a Feign client against
 * the SDK interface. Standard CRUD operations are delegated to [crud] explicitly.
 *
 * **Scope dispatch on POST** — inferred from `(body.namespaceId, body.userId)`:
 * - `(null, null)`   → platform (Super Admin only)
 * - `(ns, null)`     → namespace-scoped (WRITE on namespace)
 * - `(null, user)`   → user-global (authenticated only)
 * - `(ns, user)`     → user × namespace (READ on namespace)
 *
 * `body.userId` must equal the authenticated user's id when supplied (mass-assignment guard).
 *
 * **Mass-assignment guard on PUT**: [namespaceId], [userId] and [agentConfigId] are
 * immutable post-create — preserved from the persisted entity.
 * Mutable fields: name, description, promptContent, frequency, timeUtc, dayOfWeek, enabled.
 *
 * **Prompt lifecycle** — the controller owns prompt creation/update/deletion:
 * - POST: creates a generic Prompt (agentConfigId = null) named `{defName}-{agentName}`,
 *   then creates the CaseDefinition referencing it.
 * - PUT: updates the linked Prompt's content (and name if the definition was renamed),
 *   then updates the CaseDefinition.
 * - DELETE: deletes the CaseDefinition, then deletes the linked Prompt.
 *
 * **[search]** returns case definitions at an exact scope level — no merge, no inheritance.
 * **[effective]** returns the merged set across the four overlay layers.
 * **[toggle]** flips the enabled flag on a single case definition.
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
    override fun getById(
        @PathVariable id: UUID,
    ): CaseDefinitionDto = crud.getById(id)

    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<CaseDefinitionDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @Operation(
        summary = "List CaseDefinitions at an exact scope level",
        description =
            "Returns case definitions declared at a single exact scope level — no merge, no inheritance. " +
                "The `(namespaceId?, userId?)` combination in the body determines the level:\n\n" +
                "| namespaceId | userId   | level            | required permission        |\n" +
                "|-------------|----------|------------------|----------------------------|\n" +
                "| null        | null     | platform         | authenticated              |\n" +
                "| non-null    | null     | namespace-shared | READ on namespace          |\n" +
                "| null        | non-null | user-global      | authenticated              |\n" +
                "| non-null    | non-null | user×namespace   | READ on namespace          |\n\n" +
                "Optional `agentConfigIds` filter restricts results to case definitions linked to those agents.",
    )
    @PostMapping(
        "/search",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun search(
        @Valid @RequestBody request: CaseDefinitionSearchRequest,
    ): List<CaseDefinitionDto> {
        val currentUser = userService.getCurrentUser()
        val resolvedNamespaceId = resolveOptionalNamespaceId(request.namespaceId, request.namespaceExternalId)

        // Mass-assignment guard: userId must match the authenticated user unless super-admin
        if (request.userId != null && request.userId != currentUser.id && !currentUser.isAdmin) {
            throw AccessDeniedException("Cannot search case definitions for another user")
        }

        // Namespace-scoped levels require READ on the namespace
        if (resolvedNamespaceId != null) {
            val granted =
                permissionService.hasPermission(
                    userId = currentUser.id.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = resolvedNamespaceId.toString(),
                    action = Action.READ,
                )
            if (!granted) throw AccessDeniedException("Cannot read case definitions in namespace $resolvedNamespaceId")
        }

        return caseDefinitionService
            .findByScope(
                namespaceId = resolvedNamespaceId,
                userId = request.userId,
                agentConfigIds = request.agentConfigIds,
            ).map { def ->
                val promptContent = promptService.findById(def.promptId)?.content?.firstOrNull() ?: ""
                toDto(def, promptContent)
            }
    }

    @Operation(
        summary = "Effective case definitions for a user in a namespace",
        description =
            "Returns the resolved set of case definitions accessible in the given namespace context. " +
                "Merges platform, namespace-shared, user-global and user×namespace layers by name, " +
                "highest-priority layer wins. Optional `agentConfigId` filter applied post-resolution. " +
                "Access control enforced via agent DEPLOYED_TO graph (user must be super-admin or " +
                "member of a UserGroup to which the agent is deployed). " +
                "Requires READ on the namespace.",
    )
    @PostMapping(
        "/effective",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun effective(
        @Valid @RequestBody request: CaseDefinitionEffectiveRequest,
    ): List<CaseDefinitionDto> {
        val nsId = resolveNamespaceId(request.namespaceId, request.namespaceExternalId)
        val uId = resolveUserId(request.userId, request.userExternalId)

        val currentUser = userService.getCurrentUser()
        if (uId != currentUser.id) {
            throw BadRequestException("userId must match authenticated user")
        }

        val granted =
            permissionService.hasPermission(
                userId = currentUser.id.toString(),
                entityType = EntityType.NAMESPACE,
                entityId = nsId.toString(),
                action = Action.READ,
            )
        if (!granted) throw AccessDeniedException("Cannot read case definitions in namespace $nsId")

        return caseDefinitionService
            .findEffective(nsId, currentUser.id)
            .let { defs ->
                if (request.agentConfigId != null) {
                    defs.filter { it.agentConfigId == request.agentConfigId }
                } else {
                    defs
                }
            }.map { def ->
                val promptContent = promptService.findById(def.promptId)?.content?.firstOrNull() ?: ""
                toDto(def, promptContent)
            }
    }

    // -------------------------------------------------------------------------
    // Write endpoints
    // -------------------------------------------------------------------------

    @Operation(
        summary = "Create a CaseDefinition",
        description =
            "Scope is inferred from `(body.namespaceId, body.userId)`:\n\n" +
                "| body.namespaceId | body.userId      | scope           | required permission        |\n" +
                "|------------------|------------------|-----------------|----------------------------|\n" +
                "| null             | null             | platform        | Super Admin only           |\n" +
                "| present          | null             | namespace       | WRITE on the namespace     |\n" +
                "| null             | <currentUser.id> | user-global     | authenticated only         |\n" +
                "| present          | <currentUser.id> | user×namespace  | READ on the namespace      |\n\n" +
                "`body.userId` must equal the authenticated user's id when supplied. " +
                "A `namespaceId` that does not exist returns 404. " +
                "A generic Prompt is created automatically and linked to the CaseDefinition.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: CaseDefinitionDto,
    ): CaseDefinitionDto {
        val currentUser = userService.getCurrentUser()
        val me = currentUser.id

        // Phase 1 — validation
        if (resource.userId != null && resource.userId != me) {
            throw BadRequestException("userId in body must match authenticated user or be omitted")
        }
        validateWeeklySchedule(resource)

        // Phase 2 — scope determination
        val resolvedNs: UUID? = resource.namespaceId
        val resolvedUser: UUID? = if (resource.userId != null) me else null
        val isPlatform = resolvedNs == null && resolvedUser == null

        // Phase 3 — per-scope authorization
        when {
            isPlatform -> {
                if (!currentUser.isAdmin) {
                    throw AccessDeniedException("Platform-level CaseDefinition requires Super Admin")
                }
            }
            resolvedNs != null -> {
                val authzAction = if (resolvedUser != null) Action.READ else Action.WRITE
                val granted =
                    permissionService.hasPermission(
                        userId = me.toString(),
                        entityType = EntityType.NAMESPACE,
                        entityId = resolvedNs.toString(),
                        action = authzAction,
                    )
                if (!granted) {
                    throw AccessDeniedException(
                        "Cannot create CaseDefinition in namespace $resolvedNs (${authzAction.name} required)",
                    )
                }
            }
            // user-global: isAuthenticated() from @PreAuthorize is sufficient
        }

        // Phase 4 — namespace existence check (deferred after authz)
        if (resolvedNs != null && namespaceService.findById(resolvedNs) == null) {
            throw ResourceNotFoundException("Namespace not found: $resolvedNs")
        }

        // Phase 5 — resolve AgentConfig name for the prompt name
        val agentConfig = agentConfigService.findById(resource.agentConfigId)
            ?: throw ResourceNotFoundException("AgentConfig not found: ${resource.agentConfigId}")

        // Phase 6 — create the linked generic Prompt
        val promptName = "${resource.name}-${agentConfig.name}"
        val prompt = promptService.create(
            Prompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = resolvedNs,
                userId = resolvedUser,
                agentConfigId = null,
                name = promptName,
                content = listOf(resource.promptContent),
            ),
        )

        // Phase 7 — create the CaseDefinition
        val target =
            CaseDefinition(
                metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
                namespaceId = resolvedNs,
                userId = resolvedUser,
                agentConfigId = resource.agentConfigId,
                promptId = prompt.id,
                name = resource.name,
                description = resource.description,
                cronExpression = toCronExpression(resource),
                enabled = resource.enabled,
            )
        return toDto(caseDefinitionService.create(target), resource.promptContent)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'CaseDefinition', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: CaseDefinitionDto,
    ): CaseDefinitionDto {
        validateWeeklySchedule(resource)
        val existing =
            caseDefinitionService.findById(id)
                ?: throw ResourceNotFoundException("CaseDefinition not found: $id")

        // Update the linked Prompt: content always, name if the definition was renamed
        val existingPrompt = promptService.findById(existing.promptId)
            ?: throw ResourceNotFoundException("Prompt not found: ${existing.promptId}")
        val agentConfig = agentConfigService.findById(existing.agentConfigId)
            ?: throw ResourceNotFoundException("AgentConfig not found: ${existing.agentConfigId}")
        val newPromptName = "${resource.name}-${agentConfig.name}"
        promptService.update(
            existingPrompt.copy(
                name = newPromptName,
                content = listOf(resource.promptContent),
            ),
        )

        return toDto(caseDefinitionService.update(toDomainForUpdate(resource, existing)), resource.promptContent)
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'CaseDefinition', 'DELETE')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) {
        val existing = caseDefinitionService.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        crud.delete(id)
        promptService.delete(existing.promptId)
    }

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasPermission(#id, 'CaseDefinition', 'WRITE')")
    @HideOnAccessDenied
    @Operation(summary = "Toggle a case definition enabled/disabled")
    override fun toggle(
        @PathVariable id: UUID,
    ): CaseDefinitionDto {
        val existing = caseDefinitionService.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        val toggled = caseDefinitionService.toggle(id)
        val promptContent = promptService.findById(toggled.promptId)?.content?.firstOrNull() ?: ""
        return toDto(toggled, promptContent)
    }

    // -------------------------------------------------------------------------
    // ExternalId resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a namespace UUID from either a direct UUID or an externalId.
     * Exactly one must be provided.
     */
    private fun resolveNamespaceId(
        id: UUID?,
        externalId: String?,
    ): UUID {
        if (id != null && externalId != null) {
            throw BadRequestException("Provide namespaceId or namespaceExternalId, not both")
        }
        return id
            ?: externalId?.let {
                namespaceService.findByExternalId(it)?.metadata?.id
                    ?: throw ResourceNotFoundException("Namespace not found for externalId: $it")
            }
            ?: throw BadRequestException("namespaceId or namespaceExternalId is required")
    }

    /**
     * Resolves a namespace UUID from either a direct UUID or an externalId.
     * Both can be null (for platform-level scope).
     */
    private fun resolveOptionalNamespaceId(
        id: UUID?,
        externalId: String?,
    ): UUID? {
        if (id != null && externalId != null) {
            throw BadRequestException("Provide namespaceId or namespaceExternalId, not both")
        }
        return id
            ?: externalId?.let {
                namespaceService.findByExternalId(it)?.metadata?.id
                    ?: throw ResourceNotFoundException("Namespace not found for externalId: $it")
            }
    }

    /**
     * Resolves a user UUID from either a direct UUID or an externalId.
     * Exactly one must be provided.
     */
    private fun resolveUserId(
        id: UUID?,
        externalId: String?,
    ): UUID {
        if (id != null && externalId != null) {
            throw BadRequestException("Provide userId or userExternalId, not both")
        }
        return id
            ?: externalId?.let {
                userService.findByExternalId(it)?.metadata?.id
                    ?: throw ResourceNotFoundException("User not found for externalId: $it")
            }
            ?: throw BadRequestException("userId or userExternalId is required")
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private fun validateWeeklySchedule(resource: CaseDefinitionDto) {
        if (resource.frequency == CaseDefinitionScheduleFrequency.WEEKLY && resource.dayOfWeek == null) {
            throw ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "dayOfWeek is required when frequency is WEEKLY",
            )
        }
    }

    private fun toCronExpression(resource: CaseDefinitionDto): String =
        CronExpressionConverter.toCron(
            frequency = toServiceFrequency(resource.frequency),
            dayOfWeek = resource.dayOfWeek,
            timeUtc = resource.timeUtc,
        )

    private fun toServiceFrequency(freq: CaseDefinitionScheduleFrequency): ScheduleFrequency =
        when (freq) {
            CaseDefinitionScheduleFrequency.DAILY -> ScheduleFrequency.DAILY
            CaseDefinitionScheduleFrequency.WEEKLY -> ScheduleFrequency.WEEKLY
        }

    private fun toDomainForUpdate(
        resource: CaseDefinitionDto,
        existing: CaseDefinition,
    ): CaseDefinition =
        existing.copy(
            // Immutable: namespaceId, userId, agentConfigId, promptId
            name = resource.name,
            description = resource.description,
            cronExpression = toCronExpression(resource),
            enabled = resource.enabled,
        )

    companion object : KLogging()
}

internal fun toDto(
    entity: CaseDefinition,
    promptContent: String,
): CaseDefinitionDto {
    val cronSchedule = CronExpressionConverter.fromCron(entity.cronExpression)
    return CaseDefinitionDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        agentConfigId = entity.agentConfigId,
        promptContent = promptContent,
        name = entity.name,
        description = entity.description,
        frequency = when (cronSchedule.frequency) {
            ScheduleFrequency.DAILY -> CaseDefinitionScheduleFrequency.DAILY
            ScheduleFrequency.WEEKLY -> CaseDefinitionScheduleFrequency.WEEKLY
        },
        timeUtc = cronSchedule.timeUtc,
        dayOfWeek = cronSchedule.dayOfWeek,
        enabled = entity.enabled,
        createdBy = entity.metadata.createdBy,
        createdOn = entity.metadata.created,
        updatedBy = entity.metadata.modifiedBy,
        updatedOn = entity.metadata.modified,
    )
}
