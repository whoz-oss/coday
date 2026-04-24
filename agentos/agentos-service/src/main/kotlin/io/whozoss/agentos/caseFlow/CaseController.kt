package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.SecuredEntityController
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
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

    override fun getEntityType(): String = "Case"

    override fun checkCreatePermission(userId: String, entity: Case) {
        // To create a Case, the user must have WRITE permission on the parent namespace
        if (!permissionService.hasPermission(userId, "Namespace", entity.namespaceId.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied - no write permission on namespace")
        }
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

    override fun toDomain(resource: CaseResource): Case =
        Case(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            status = resource.status,
            title = resource.title ?: "",
        )

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

    companion object : KLogging()
}

data class AddMessageRequest(
    val content: String,
    val answerToEventId: UUID? = null,
)
