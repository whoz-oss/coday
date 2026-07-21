package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.permissions.StarredService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.case.AddMessageRequest
import io.whozoss.agentos.sdk.api.case.CaseApi
import io.whozoss.agentos.sdk.api.case.CaseDto
import io.whozoss.agentos.sdk.api.case.ListByUserInNamespaceRequest
import io.whozoss.agentos.sdk.caseEvent.MessageContent
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
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * REST API for managing [Case] entities. Implements [CaseApi] so external consumers
 * can declare a Feign client against the SDK interface.
 *
 * Authorization declared via `@PreAuthorize`:
 * - READ on Case: namespace ADMIN (transitive) OR direct ADMIN/MEMBER on the case (FR15)
 * - WRITE/DELETE on Case: same permission model
 * - CREATE: namespace **READ** (FR — a MEMBER can create their own case;
 *   the ADMIN/MEMBER grant on the new case is auto-applied in the body)
 *
 * [userService] and [permissionService] are used for two non-authorization concerns:
 * (1) the auto-ADMIN grant on the new case after create, (2) the fast-path branch in
 * [listByParent] (namespace ADMIN → unfiltered listing).
 */
@RestController
@RequestMapping(
    "/api/cases",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CaseController(
    private val caseService: CaseService,
    private val caseEventService: CaseEventService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
    private val starredService: StarredService,
) : CaseApi {

    private val crud = EntityCrudDelegate(
        service = caseService,
        userService = userService,
        permissions = permissionService,
        entityType = EntityType.CASE,
        toResource = { toDto(it as Case) },
        toDomain = { resource ->
            val metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID())
            Case(
                metadata = metadata,
                namespaceId = resource.namespaceId,
                status = resource.status,
                title = resource.title ?: "Case ${metadata.id}",
            )
        },
    )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Case', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): CaseDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<CaseDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    /**
     * GET /api/cases/by-parentId/{parentId} — list cases in a namespace.
     *
     * Two performance paths:
     * 1. Namespace ADMIN → unfiltered listing (single query, no per-case check).
     * 2. Otherwise → permission-filtered listing via [CaseService.findAccessibleByUserInNamespace]
     *    which respects FR15 (MEMBERs see only their own cases or those they were directly
     *    granted READ on).
     */
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<CaseDto> {
        val user = userService.getCurrentUser()
        val userId = user.id.toString()
        val isNamespaceAdmin =
            permissionService.hasPermission(
                userId,
                EntityType.NAMESPACE,
                parentId.toString(),
                Action.WRITE,
            )
        val cases =
            if (isNamespaceAdmin) {
                logger.debug { "User $userId is namespace-ADMIN on $parentId — short-circuit list (no filtering)" }
                caseService.findByParent(parentId)
            } else {
                logger.debug { "User $userId not namespace-ADMIN on $parentId — using permission-filtered listing" }
                caseService.findAccessibleByUserInNamespace(user.id, parentId)
            }
        return withCallerMeta(cases, userId)
    }

    /**
     * GET /api/cases/by-parentId/{parentId}/mine — list ONLY the cases in [parentId]
     * that the CURRENT user has a DIRECT [:ADMIN|MEMBER] relation with.
     *
     * Deliberately excludes the namespace-admin fast path AND namespace-admin
     * transitivity used by [listByParent]: every returned case is one the user can
     * star (star requires a direct user↔case edge). This powers the AgentOS drawer.
     *
     * Single-case access by URL is unchanged ([getById] gates on `hasPermission(#id,'Case','READ')`),
     * so admins/super-admins can still open a case they don't own.
     */
    @GetMapping("/by-parentId/{parentId}/mine")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    fun listMineByParent(
        @PathVariable parentId: UUID,
    ): List<CaseDto> {
        val user = userService.getCurrentUser()
        logger.debug { "Listing directly-related cases for user ${user.id} in namespace $parentId" }
        val cases = caseService.findConcerningUserInNamespace(user.id, parentId)
        return withCallerMeta(cases, user.id.toString())
    }

    /**
     * Map domain [cases] to [CaseDto]s, enriching each with [userId]'s direct
     * relation (`role`), favorite flag, and [CaseDto.lastMessageAt].
     *
     * Two batch queries resolve the whole set (no per-case round-trips):
     * - [StarredService.listDirectRelations] for role/favorite metadata
     * - [CaseEventService.findLastMessageTimestamps] for the last-message timestamp
     *   used by the frontend to sort and group conversations.
     *
     * Cases the user has no direct edge on get `role = null` and `favorite = false`
     * (e.g. the namespace-admin fast path in [listByParent]).
     */
    private fun withCallerMeta(
        cases: List<Case>,
        userId: String,
    ): List<CaseDto> {
        val starred = starredService.listDirectRelations(userId, EntityType.CASE)
        val lastMessageTimestamps = caseEventService.findLastMessageTimestamps(cases.map { it.id })
        return cases.map {
            val meta = starred[it.metadata.id.toString()]
            toDto(it).copy(
                favorite = meta?.starred ?: false,
                role = meta?.relation?.name,
                lastMessageAt = lastMessageTimestamps[it.id],
            )
        }
    }

    /**
     * POST /api/cases — create a Case. Permission gate is namespace READ (a MEMBER may
     * create their own cases). After persistence, auto-grants ADMIN on the new case to
     * the creator (best-effort, non-transactional).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'READ')")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: CaseDto,
    ): CaseDto {
        val created = crud.create(resource)
        val caseId = created.id ?: error("Created case must have an id")
        val userId = userService.getCurrentUser().id.toString()
        val granted =
            runCatching {
                permissionService.grantPermission(
                    userId,
                    EntityType.CASE,
                    caseId.toString(),
                    PermissionRelation.ADMIN,
                )
            }.onFailure { e ->
                logger.warn(e) {
                    "Auto-ADMIN grant failed for case $caseId (user $userId) — case persisted. " +
                        "Recovery: a super-admin or namespace ADMIN must grant ADMIN on the case manually."
                }
            }.isSuccess
        if (granted) {
            logger.info { "User $userId created case $caseId with auto-ADMIN grant" }
        }
        // Surface the creator's fresh direct relation so the UI enables ADMIN-only actions
        // (delete) on the new case immediately, without waiting for a list refresh to enrich it.
        return if (granted) created.copy(role = PermissionRelation.ADMIN.name) else created
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Case', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: CaseDto,
    ): CaseDto {
        val existing = caseService.findById(id)
            ?: throw ResourceNotFoundException("Case not found: $id")
        return toDto(
            caseService.update(
                existing.copy(
                    // namespaceId and status are mass-assignment-guarded:
                    // namespaceId is the transitivity key for permissions;
                    // status is driven by the runtime lifecycle, not PUT.
                    title = resource.title ?: existing.title,
                ),
            ),
        )
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Case', 'DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    @GetMapping("/by-user/{userId}")
    override fun listByUser(
        @PathVariable userId: UUID,
    ): List<CaseDto> {
        logger.debug { "Listing cases for user $userId" }
        // No caller-meta enrichment: these list a *target* user's cases, so role/favorite
        // (defined as the caller's) would be misleading; they stay at their defaults.
        return caseService.findConcerningUser(userId).map(::toDto)
    }

    @GetMapping("/by-user/external/{externalId}")
    override fun listByUserExternalId(
        @PathVariable externalId: String,
    ): List<CaseDto> {
        val user = userService.findByExternalId(externalId)
            ?: throw ResourceNotFoundException("User not found: $externalId")
        logger.debug { "Listing cases for user ${user.id} (externalId=$externalId)" }
        return caseService.findConcerningUser(user.id).map(::toDto)
    }

    @PostMapping("/by-user/in-namespace", consumes = [MediaType.APPLICATION_JSON_VALUE])
    override fun listByUserInNamespace(
        @RequestBody request: ListByUserInNamespaceRequest,
    ): List<CaseDto> {
        val user = userService.findByExternalId(request.userExternalId)
            ?: throw ResourceNotFoundException("User not found: ${request.userExternalId}")
        val namespace = namespaceService.findByExternalId(request.namespaceExternalId)
            ?: throw ResourceNotFoundException("Namespace not found: ${request.namespaceExternalId}")
        logger.debug { "Listing cases for user ${user.id} in namespace ${namespace.id}" }
        return caseService.findConcerningUserInNamespace(user.id, namespace.id).map(::toDto)
    }

    @PostMapping("/{caseId}/messages")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) {
        val user = userService.getCurrentUser()
        logger.info { "Adding message to case: $caseId" }
        val displayName = listOfNotNull(user.firstname, user.lastname)
            .joinToString(" ")
            .ifBlank { user.metadata.id.toString() }
        val userActor = Actor(id = user.metadata.id.toString(), displayName = displayName, role = ActorRole.USER)
        caseService.addMessage(
            caseId = caseId,
            actor = userActor,
            content = listOf(MessageContent.Text(request.content)),
            answerToEventId = request.answerToEventId,
            sessionContext = request.sessionContext,
        )
        logger.info { "Message added to case: $caseId" }
    }

    @PostMapping("/{caseId}/interrupt")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun interruptCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Interrupting case: $caseId" }
        caseService.interruptCase(caseId)
        logger.info { "Case interrupted: $caseId" }
    }

    @PostMapping("/{caseId}/kill")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'DELETE')")
    override fun killCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Killing case: $caseId" }
        caseService.killCase(caseId)
        logger.info { "Case killed: $caseId" }
    }

    /** PUT /api/cases/{id}/star — mark the case as favorite for the current user. */
    @PutMapping("/{id}/star")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#id, 'Case', 'READ')")
    fun starCase(
        @PathVariable id: UUID,
    ) {
        val userId = userService.getCurrentUser().id.toString()
        if (!starredService.setStarred(userId, EntityType.CASE, id.toString(), true)) {
            // READ can be granted transitively (namespace-admin) but starring requires a
            // direct user↔case edge — reject instead of reporting a success that did not persist.
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot star case $id: the caller has no direct relation on it",
            )
        }
        logger.info { "User $userId starred case $id" }
    }

    /** DELETE /api/cases/{id}/star — remove the case from the current user's favorites. */
    @DeleteMapping("/{id}/star")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'Case', 'READ')")
    fun unstarCase(
        @PathVariable id: UUID,
    ) {
        val userId = userService.getCurrentUser().id.toString()
        if (!starredService.setStarred(userId, EntityType.CASE, id.toString(), false)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot unstar case $id: the caller has no direct relation on it",
            )
        }
        logger.info { "User $userId unstarred case $id" }
    }

    companion object : KLogging()
}

internal fun toDto(entity: Case) =
    CaseDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        status = entity.status,
        title = entity.title,
        parentCaseId = entity.parentCaseId,
        created = entity.metadata.created,
        modified = entity.metadata.modified,
        // lastMessageAt is not stored on Case — it is resolved at list time by
        // withCallerMeta via CaseEventService.findLastMessageTimestamps and injected
        // via .copy() after this mapping. Single-case endpoints leave it null.
        removed = entity.metadata.removed,
    )
