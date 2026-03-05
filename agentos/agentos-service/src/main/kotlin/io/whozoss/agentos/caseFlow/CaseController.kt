package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
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
) : EntityController<Case, UUID>(caseService) {

    /** POST /api/cases/{caseId}/messages — add a user message to a running case. */
    @PostMapping("/{caseId}/messages")
    fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) {
        val userActor = Actor(id = request.userId, displayName = request.userId, role = ActorRole.USER)
        caseService.addMessage(
            caseId = caseId,
            actor = userActor,
            content = listOf(MessageContent.Text(request.content)),
            answerToEventId = request.answerToEventId,
        )
        logger.info { "Message added to case: $caseId" }
    }

    /** POST /api/cases/{caseId}/stop — stop a case gracefully. */
    @PostMapping("/{caseId}/stop")
    fun stopCase(@PathVariable caseId: UUID) {
        caseService.stopCase(caseId)
        logger.info { "Case stopped: $caseId" }
    }

    /** POST /api/cases/{caseId}/kill — kill a case immediately. */
    @PostMapping("/{caseId}/kill")
    fun killCase(@PathVariable caseId: UUID) {
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
