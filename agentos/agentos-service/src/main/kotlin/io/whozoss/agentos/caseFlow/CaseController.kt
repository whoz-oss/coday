package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.auth.AuthorizationService
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.auth.Operation
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing Cases.
 *
 * Extends [EntityController] with [CaseResource] as the HTTP DTO.
 * All endpoints enforce authorization via [AuthorizationService].
 */
@RestController
@RequestMapping(
    "/api/cases",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CaseController(
    private val caseService: CaseService,
    private val userService: UserService,
    private val authorizationService: AuthorizationService,
    private val roleRepository: RoleRepository,
) : EntityController<Case, UUID, CaseResource>(caseService) {

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

    override fun toDomain(resource: CaseResource): Case =
        Case(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            status = resource.status,
            title = resource.title ?: "",
        )

    // -------------------------------------------------------------------------
    // Overridden CRUD with authorization
    // -------------------------------------------------------------------------

    override fun getById(@PathVariable id: UUID): CaseResource {
        authorizationService.requireCaseAccess(currentUserId(), id.toString(), Operation.READ)
        return super.getById(id)
    }

    override fun getByIds(@RequestBody ids: List<UUID>): List<CaseResource> {
        val userId = currentUserId()
        return service.findByIds(ids)
            .filter {
                try {
                    authorizationService.requireCaseAccess(userId, it.id.toString(), Operation.READ)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            .map { toResource(it) }
    }

    /**
     * POST /api/cases — create a new case.
     *
     * Requires MEMBER access on the parent namespace.
     * After creation, the creator is auto-assigned OWNER on the case.
     */
    override fun create(@Valid @RequestBody resource: CaseResource): CaseResource {
        val userId = currentUserId()
        authorizationService.requireNamespaceAccess(userId, resource.namespaceId.toString(), NamespaceRole.MEMBER)

        val created = service.create(toDomain(resource))
        val createdCaseId = created.id.toString()

        roleRepository.assignCaseRole(userId, createdCaseId, CaseRole.OWNER, userId)
        logger.info { "Auto-assigned case OWNER to user $userId on case $createdCaseId" }

        return toResource(created)
    }

    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: CaseResource): CaseResource {
        authorizationService.requireCaseAccess(currentUserId(), id.toString(), Operation.WRITE)
        return super.update(id, resource)
    }

    override fun delete(@PathVariable id: UUID) {
        authorizationService.requireCaseAccess(currentUserId(), id.toString(), Operation.DELETE)
        super.delete(id)
    }

    /**
     * GET /api/cases/by-parentId/{parentId} — list cases in a namespace.
     *
     * Filtered by [AuthorizationService.filterAccessibleCaseIds].
     */
    override fun listByParent(@PathVariable parentId: UUID): List<CaseResource> {
        val userId = currentUserId()
        val accessibleCaseIds = authorizationService.filterAccessibleCaseIds(userId, parentId.toString())
        return service.findByParent(parentId)
            .filter { it.id.toString() in accessibleCaseIds }
            .map { toResource(it) }
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
        val user = userService.getCurrentUser()
        authorizationService.requireCaseAccess(user.id.toString(), caseId.toString(), Operation.EXECUTE)

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
     * Requires MANAGE permission (OWNER role).
     */
    @PostMapping("/{caseId}/interrupt")
    fun interruptCase(
        @PathVariable caseId: UUID,
    ) {
        authorizationService.requireCaseAccess(currentUserId(), caseId.toString(), Operation.MANAGE)
        logger.info { "Interrupting case: $caseId" }
        caseService.interruptCase(caseId)
        logger.info { "Case interrupted: $caseId" }
    }

    /** POST /api/cases/{caseId}/kill — permanently terminate a case and evict its runtime. */
    @PostMapping("/{caseId}/kill")
    fun killCase(
        @PathVariable caseId: UUID,
    ) {
        authorizationService.requireCaseAccess(currentUserId(), caseId.toString(), Operation.MANAGE)
        logger.info { "Killing case: $caseId" }
        caseService.killCase(caseId)
        logger.info { "Case killed: $caseId" }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()

    companion object : KLogging()
}

data class AddMessageRequest(
    val content: String,
    val answerToEventId: UUID? = null,
)
