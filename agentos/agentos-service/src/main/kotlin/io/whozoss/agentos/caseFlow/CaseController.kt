package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PostFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [Case] entities (Epic 5 declarative migration).
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
    private val userService: UserService,
    private val permissionService: PermissionService,
) : EntityController<Case, UUID, CaseResource>(caseService) {

    override fun toResource(entity: Case): CaseResource =
        CaseResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            status = entity.status,
            title = entity.title,
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
    override fun getById(@PathVariable id: UUID): CaseResource = super.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PostFilter("hasPermission(filterObject.id, 'Case', 'READ')")
    override fun getByIds(@RequestBody ids: List<UUID>): List<CaseResource> = super.getByIds(ids)

    /**
     * GET /api/cases/by-parentId/{parentId} — list cases in a namespace (Story 3.2/3.3).
     *
     * `@PreAuthorize` gates on namespace READ ; inside the body, two perf paths:
     * 1. Namespace ADMIN → unfiltered listing (single query, no per-case check).
     * 2. Otherwise → permission-filtered listing via dedicated Cypher
     *    (`findAccessibleByUserInNamespace`) which respects FR15 (MEMBERs see only
     *    their own cases or those they were directly granted READ on).
     */
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(@PathVariable parentId: UUID): List<CaseResource> {
        val user = userService.getCurrentUser()
        val userId = user.id.toString()
        val isNamespaceAdmin = permissionService.hasPermission(
            userId,
            NAMESPACE_TYPE,
            parentId.toString(),
            Action.WRITE,
        )
        return if (isNamespaceAdmin) {
            logger.debug { "User $userId is namespace-ADMIN on $parentId — short-circuit list (no filtering)" }
            caseService.findByParent(parentId).map { toResource(it) }
        } else {
            logger.debug { "User $userId not namespace-ADMIN on $parentId — using permission-filtered listing" }
            caseService.findAccessibleByUserInNamespace(user.id, parentId).map { toResource(it) }
        }
    }

    /**
     * POST /api/cases — create a Case. Permission gate is namespace READ
     * (a MEMBER may create their own cases). The body delegates to the standard
     * `EntityController.create`, then auto-grants ADMIN on the new case to the
     * creator (Story 3.1 AC1). The grant is best-effort (non-transactional).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'READ')")
    override fun create(@Valid @RequestBody resource: CaseResource): CaseResource {
        val created = super.create(resource)
        val caseId = created.id ?: error("Created case must have an id")
        val userId = userService.getCurrentUser().id.toString()
        runCatching {
            permissionService.grantPermission(
                userId,
                ENTITY_TYPE,
                caseId.toString(),
                PermissionRelation.ADMIN,
            )
            logger.info { "User $userId created case $caseId with auto-ADMIN grant" }
        }.onFailure { e ->
            logger.warn(e) {
                "Auto-ADMIN grant failed for case $caseId (user $userId) — case persisted. " +
                    "Recovery: a super-admin or namespace ADMIN must grant ADMIN on the case manually."
            }
        }
        return created
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Case', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: CaseResource,
    ): CaseResource {
        val existing = caseService.findById(id)
            ?: throw io.whozoss.agentos.exception.ResourceNotFoundException("Case not found: $id")
        return toResource(caseService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Case', 'DELETE')")
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    /** POST /api/cases/{caseId}/messages — add a user message to a running case. */
    @PostMapping("/{caseId}/messages")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun addMessage(
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
        )
        logger.info { "Message added to case: $caseId" }
    }

    /**
     * POST /api/cases/{caseId}/interrupt — interrupt the current agent turn and
     * return the case to IDLE. Requires WRITE on the case.
     */
    @PostMapping("/{caseId}/interrupt")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun interruptCase(@PathVariable caseId: UUID) {
        logger.info { "Interrupting case: $caseId" }
        caseService.interruptCase(caseId)
        logger.info { "Case interrupted: $caseId" }
    }

    /** POST /api/cases/{caseId}/kill — permanently terminate a case. Requires DELETE. */
    @PostMapping("/{caseId}/kill")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'DELETE')")
    fun killCase(@PathVariable caseId: UUID) {
        logger.info { "Killing case: $caseId" }
        caseService.killCase(caseId)
        logger.info { "Case killed: $caseId" }
    }

    companion object : KLogging() {
        private const val ENTITY_TYPE = "Case"
        private const val NAMESPACE_TYPE = "Namespace"
    }
}

data class AddMessageRequest(
    val content: String,
    val answerToEventId: UUID? = null,
)
