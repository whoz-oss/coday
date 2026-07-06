package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
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

/**
 * REST API for managing [Case] entities.
 *
 * Authorization declared via `@PreAuthorize`:
 * - READ on Case: namespace ADMIN (transitive) OR direct ADMIN/MEMBER on the case (FR15)
 * - WRITE/DELETE on Case: same permission model
 * - CREATE: namespace **READ** (FR — a MEMBER can create their own case;
 *   the ADMIN/MEMBER grant on the new case is auto-applied in the body)
 *
 * `userService` and `permissionService` are still injected for two non-authorization
 * concerns: (1) the auto-ADMIN grant on the new case after create, (2) the fast-path
 * branch in `listByParent` (namespace ADMIN → unfiltered listing) which is a
 * performance optimization, not an authorization check.
 */
@RestController
@RequestMapping(
    "/api/cases",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CaseController(
    private val caseService: CaseService,
    private val namespaceService: NamespaceService,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<Case, UUID, CaseResource>(caseService, userService, permissionService) {
    override val entityType = EntityType.CASE

    override fun toResource(entity: Case): CaseResource =
        CaseResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            status = entity.status,
            title = entity.title,
            parentCaseId = entity.parentCaseId,
            created = entity.metadata.created,
            removed = entity.metadata.removed,
        )

    override fun toDomain(resource: CaseResource): Case {
        val metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID())
        return Case(
            metadata = metadata,
            namespaceId = resource.namespaceId,
            status = resource.status,
            title = resource.title ?: "Case ${metadata.id}",
        )
    }

    /**
     * Merge an update resource onto an existing persisted entity. The persisted
     * [Case.namespaceId] and [Case.status] are preserved (mass-assignment guard):
     * - `namespaceId` is the transitivity key for permissions (FR15) — clients
     *   must not relocate a Case across namespaces via PUT.
     * - `status` is a lifecycle field driven by the runtime (`interruptCase`,
     *   `killCase`, agent execution) — clients must not transition state via PUT.
     */
    private fun toDomainForUpdate(
        resource: CaseResource,
        existing: Case,
    ): Case =
        existing.copy(
            title = resource.title ?: existing.title,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Case', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): CaseResource = super.getById(id)

    // POST /by-ids — inherited from EntityController.getByIds (story 5-4 factorisation).

    /**
     * GET /api/cases/by-parentId/{parentId} — list cases in a namespace (/3.3).
     *
     * `@PreAuthorize` gates on namespace READ ; inside the body, two perf paths:
     * 1. Namespace ADMIN → unfiltered listing (single query, no per-case check).
     * 2. Otherwise → permission-filtered listing via dedicated Cypher
     *    (`findAccessibleByUserInNamespace`) which respects FR15 (MEMBERs see only
     *    their own cases or those they were directly granted READ on).
     */
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<CaseResource> {
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
    ): List<CaseResource> {
        val user = userService.getCurrentUser()
        logger.debug { "Listing directly-related cases for user ${user.id} in namespace $parentId" }
        val cases = caseService.findConcerningUserInNamespace(user.id, parentId)
        return withCallerMeta(cases, user.id.toString())
    }

    /**
     * Map domain [cases] to [CaseResource]s, enriching each with [userId]'s direct
     * relation (`role`) and favorite flag. A single companion query resolves the whole
     * set (no per-case round-trip). Cases the user has no direct edge on get `role = null`
     * and `favorite = false` (e.g. the namespace-admin fast path in [listByParent]).
     */
    private fun withCallerMeta(
        cases: List<Case>,
        userId: String,
    ): List<CaseResource> {
        val relations = permissionService.listDirectRelations(userId, EntityType.CASE)
        return cases.map {
            val meta = relations[it.metadata.id.toString()]
            toResource(it).copy(favorite = meta?.starred ?: false, role = meta?.relation?.name)
        }
    }

    /**
     * POST /api/cases — create a Case. Permission gate is namespace READ
     * (a MEMBER may create their own cases). The body delegates to the standard
     * `EntityController.create`, then auto-grants ADMIN on the new case to the
     * creator. The grant is best-effort (non-transactional).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'READ')")
    override fun create(
        @Valid @RequestBody resource: CaseResource,
    ): CaseResource {
        val created = super.create(resource)
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
        @Valid @RequestBody resource: CaseResource,
    ): CaseResource {
        val existing =
            caseService.findById(id)
                ?: throw io.whozoss.agentos.exception
                    .ResourceNotFoundException("Case not found: $id")
        return toResource(caseService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Case', 'DELETE')")
    override fun delete(
        @PathVariable id: UUID,
    ) = super.delete(id)

    /**
     * GET /api/cases/by-user/{userId} — list all cases concerning a specific user
     * across every namespace.
     *
     * A case concerns a user when they have a direct ADMIN or MEMBER relation on it.
     */
    @GetMapping("/by-user/{userId}")
    fun listByUser(
        @PathVariable userId: UUID,
    ): List<CaseResource> {
        logger.debug { "Listing cases for user $userId" }
        return withCallerMeta(caseService.findConcerningUser(userId), userId.toString())
    }

    /**
     * GET /api/cases/by-user/external/{externalId} — list all cases concerning a user
     * identified by their external identity-provider key, across every namespace.
     *
     * Resolves the user from [externalId], then delegates to [listByUser] logic.
     * Returns 404 if no user matches the external id.
     */
    @GetMapping("/by-user/external/{externalId}")
    fun listByUserExternalId(
        @PathVariable externalId: String,
    ): List<CaseResource> {
        val user =
            userService.findByExternalId(externalId)
                ?: throw io.whozoss.agentos.exception
                    .ResourceNotFoundException("User not found: $externalId")
        logger.debug { "Listing cases for user ${user.id} (externalId=$externalId)" }
        return withCallerMeta(caseService.findConcerningUser(user.id), user.id.toString())
    }

    /**
     * POST /api/cases/by-user/in-namespace — list cases concerning a user scoped to a
     * single namespace, identified by their respective external IDs.
     *
     * Resolves the user from [ListByUserInNamespaceRequest.externalId] and the namespace
     * from [ListByUserInNamespaceRequest.namespaceExternalId], then returns only cases
     * at the intersection. Returns 404 if no user or namespace matches.
     */
    @PostMapping("/by-user/in-namespace", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun listByUserInNamespace(
        @RequestBody request: ListByUserInNamespaceRequest,
    ): List<CaseResource> {
        val user =
            userService.findByExternalId(request.userExternalId)
                ?: throw io.whozoss.agentos.exception
                    .ResourceNotFoundException("User not found: ${request.userExternalId}")
        val namespace =
            namespaceService.findByExternalId(request.namespaceExternalId)
                ?: throw io.whozoss.agentos.exception
                    .ResourceNotFoundException("Namespace not found: ${request.namespaceExternalId}")
        logger.debug { "Listing cases for user ${user.id} in namespace ${namespace.id}" }
        return withCallerMeta(caseService.findConcerningUserInNamespace(user.id, namespace.id), user.id.toString())
    }

    /** POST /api/cases/{caseId}/messages — add a user message to a running case. */
    @PostMapping("/{caseId}/messages")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) {
        val user = userService.getCurrentUser()
        logger.info { "Adding message to case: $caseId" }
        val displayName =
            listOfNotNull(user.firstname, user.lastname)
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

    /**
     * POST /api/cases/{caseId}/interrupt — interrupt the current agent turn and
     * return the case to IDLE. Requires WRITE on the case.
     */
    @PostMapping("/{caseId}/interrupt")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun interruptCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Interrupting case: $caseId" }
        caseService.interruptCase(caseId)
        logger.info { "Case interrupted: $caseId" }
    }

    /** POST /api/cases/{caseId}/kill — permanently terminate a case. Requires DELETE. */
    @PostMapping("/{caseId}/kill")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'DELETE')")
    fun killCase(
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
        if (!permissionService.setStarred(userId, EntityType.CASE, id.toString(), true)) {
            // READ can be granted transitively (namespace-admin) but starring writes on the
            // caller's direct edge — reject instead of reporting a success that did not persist.
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
        if (!permissionService.setStarred(userId, EntityType.CASE, id.toString(), false)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot unstar case $id: the caller has no direct relation on it",
            )
        }
        logger.info { "User $userId unstarred case $id" }
    }

    companion object : KLogging()
}

data class ListByUserInNamespaceRequest(
    val userExternalId: String,
    val namespaceExternalId: String,
)

data class AddMessageRequest(
    val content: String,
    val answerToEventId: UUID? = null,
    /** Opaque application context at the time the user sent this message. Embedded on [io.whozoss.agentos.sdk.caseEvent.MessageEvent.sessionContext]. */
    val sessionContext: Map<String, Any?>? = null,
)
