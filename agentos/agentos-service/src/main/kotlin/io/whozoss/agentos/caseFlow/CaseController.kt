package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.SecuredEntityController
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping(
    "/api/cases",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CaseController(
    private val caseService: CaseService,
    userService: UserService,
    permissionService: PermissionService,
) : SecuredEntityController<Case, UUID, CaseResource>(caseService, userService, permissionService) {

    override fun getEntityType(): String = ENTITY_TYPE

    /**
     * To create a Case, the caller must have at least READ on the parent namespace
     * (i.e. hold a MEMBER or ADMIN relation, or be super-admin via bypass). This
     * matches Story 3.1 AC1, where a namespace MEMBER may create their own cases.
     *
     * Note: [Action.WRITE] would require a namespace ADMIN relation per
     * [io.whozoss.agentos.permissions.PermissionServiceImpl.evaluatePermission] —
     * too strict for this use case.
     */
    override fun checkCreatePermission(userId: String, entity: Case) {
        if (!permissionService.hasPermission(userId, NAMESPACE_TYPE, entity.namespaceId.toString(), Action.READ)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied - no access to namespace")
        }
    }

    /**
     * GET /api/cases/by-parentId/{parentId} — list cases in a namespace.
     *
     * Two fast paths, both avoiding the N+1 `hasPermission` cost of
     * [io.whozoss.agentos.entity.SecuredEntityController.listByParent]:
     *
     * 1. Namespace ADMIN short-circuit (Story 3.2): if the caller holds the ADMIN
     *    relation on the parent namespace (or is super-admin via bypass), they
     *    transitively ADMIN every case — return `findByParent` unfiltered in a
     *    single query. The short-circuit uses `hasPermission(Action.WRITE)` which
     *    maps to `PermissionRelation.ADMIN` in
     *    [io.whozoss.agentos.permissions.PermissionServiceImpl.evaluatePermission];
     *    if a future permission model adds a non-ADMIN WRITE grant (e.g. an
     *    "editor" relation), revisit this check to avoid leaking every case.
     * 2. Permission-filtered listing (Story 3.3): otherwise delegate to a
     *    dedicated Cypher query that returns only cases with a direct
     *    ADMIN/MEMBER relation on the case OR transitive ADMIN via namespace.
     *    Namespace MEMBERs do NOT gain transitive READ on cases (FR15).
     */
    override fun listByParent(@PathVariable parentId: UUID): List<CaseResource> {
        val user = userService.getCurrentUser()
        val userId = user.id.toString()
        val isNamespaceAdmin = permissionService.hasPermission(
            userId,
            NAMESPACE_TYPE,
            parentId.toString(),
            Action.WRITE,
        )
        if (isNamespaceAdmin) {
            logger.debug {
                "User $userId is namespace-ADMIN on $parentId — short-circuit list (no filtering)"
            }
            return caseService.findByParent(parentId).map { toResource(it) }
        }
        logger.debug {
            "User $userId is not namespace-ADMIN on $parentId — using permission-filtered listing"
        }
        return caseService
            .findAccessibleByUserInNamespace(user.id, parentId)
            .map { toResource(it) }
    }

    /**
     * POST /api/cases — delegates creation to the parent (which invokes
     * [checkCreatePermission]), then auto-grants an ADMIN relationship on the new
     * case to the creator (Story 3.1 AC1).
     *
     * The grant is best-effort (non-transactional) — same pattern as Story 2.1 on
     * namespace creation. A grant failure is logged at WARN; the case stays
     * persisted. Super-admins keep their bypass regardless.
     */
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
            // The case is persisted but the creator lost their direct ADMIN grant.
            // For a super-admin or namespace ADMIN creator this is harmless (they
            // retain access via bypass / transitivity). For a namespace MEMBER
            // creator this is a LOCKOUT: after Story 3.3 (FR15) MEMBERs do not
            // inherit transitive READ on cases, so only a super-admin or
            // namespace ADMIN can recover access by manually granting the relation.
            logger.warn(e) {
                "Auto-ADMIN grant failed for case $caseId (user $userId) — case persisted. " +
                    "Recovery: a super-admin or namespace ADMIN must grant ADMIN on the case manually."
            }
        }
        return created
    }

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

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
            // Mirror the Case data-class default "Case $id" when the client omits
            // a title, instead of persisting an empty string (Story 3.4 AC4).
            title = resource.title ?: "Case ${metadata.id}",
        )
    }

    // -------------------------------------------------------------------------
    // Additional endpoints
    // -------------------------------------------------------------------------

    /** POST /api/cases/{caseId}/messages — add a user message to a running case. */
    @PostMapping("/{caseId}/messages")
    fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) {
        // Check that the user has WRITE permission on the case
        val user = userService.getCurrentUser()
        val userId = user.id.toString()

        if (!permissionService.hasPermission(userId, getEntityType(), caseId.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

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
     * POST /api/cases/{caseId}/interrupt
     *
     * Interrupt the current agent turn and return the case to IDLE.
     * The runtime and SSE connection stay open — the user can send a corrective
     * message immediately. Use this when the agent is going in the wrong direction.
     */
    @PostMapping("/{caseId}/interrupt")
    fun interruptCase(
        @PathVariable caseId: UUID,
    ) {
        // Check that the user has WRITE permission on the case
        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, getEntityType(), caseId.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        logger.info { "Interrupting case: $caseId" }
        caseService.interruptCase(caseId)
        logger.info { "Case interrupted: $caseId" }
    }

    /** POST /api/cases/{caseId}/kill — permanently terminate a case and evict its runtime. */
    @PostMapping("/{caseId}/kill")
    fun killCase(
        @PathVariable caseId: UUID,
    ) {
        // Check that the user has DELETE permission on the case
        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, getEntityType(), caseId.toString(), Action.DELETE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

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
