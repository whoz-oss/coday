package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserController.Companion.X_EXTERNAL_ID_HEADER
import io.whozoss.agentos.user.UserService
import jakarta.servlet.http.HttpServletRequest
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping(
    "/api/cases",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CaseController(
    private val caseService: CaseService,
    private val userService: UserService,
    /** Thread-local proxy — safe to inject as a field in a Spring MVC controller. */
    private val httpServletRequest: HttpServletRequest,
) : EntityController<Case, UUID>(caseService) {
    /**
     * POST /api/cases — create a new case, linking it to the caller's user record.
     *
     * Overrides [EntityController.create] to additionally read the caller's external
     * identity from [X_EXTERNAL_ID_HEADER]. Spring injects [HttpServletRequest] as
     * a thread-local proxy bean — no mapping change is needed.
     *
     * If the header is absent or no user record exists for that identity, the case
     * is created with [Case.createdByUserId] = null so that existing clients
     * (dev mode, tests) are unaffected.
     */
    override fun create(
        @RequestBody entity: Case,
    ): Case {
        val externalId = httpServletRequest.getHeader(X_EXTERNAL_ID_HEADER)?.takeIf { it.isNotBlank() }
        val ownerUserId = externalId?.let { userService.findByExternalId(it)?.id }
        val caseToCreate = entity.copy(
            metadata = EntityMetadata(
                id = entity.metadata.id,
                createdBy = externalId,
            ),
            createdByUserId = ownerUserId,
        )
        logger.info { "Creating case for namespace ${entity.namespaceId}, externalId=$externalId, ownerUserId=$ownerUserId" }
        return caseService.create(caseToCreate)
    }

    /** POST /api/cases/{caseId}/messages — add a user message to a running case. */
    @PostMapping("/{caseId}/messages")
    fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) {
        logger.info { "Adding message to case: $caseId" }
        val userActor = Actor(id = request.userId, displayName = request.userId, role = ActorRole.USER)
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
        logger.info { "Interrupting case: $caseId" }
        caseService.interruptCase(caseId)
        logger.info { "Case interrupted: $caseId" }
    }

    /** POST /api/cases/{caseId}/kill — permanently terminate a case and evict its runtime. */
    @PostMapping("/{caseId}/kill")
    fun killCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Killing case: $caseId" }
        caseService.killCase(caseId)
        logger.info { "Case killed: $caseId" }
    }

    companion object : KLogging()
}

data class AddMessageRequest(
    val content: String,
    val userId: String = "default-user",
    val answerToEventId: UUID? = null,
)
