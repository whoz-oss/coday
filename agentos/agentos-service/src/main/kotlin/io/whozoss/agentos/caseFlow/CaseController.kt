package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing Cases.
 *
 * Extends EntityController<CaseModel, UUID> for standard CRUD.
 * CaseService manages both CaseModel persistence and Case runtime instances.
 *
 * Endpoints (inherited):
 * - GET    /api/cases/{id}             — get a case
 * - GET    /api/cases?ids=a,b,c        — get multiple cases by IDs
 * - GET    /api/cases?parentId=xxx     — list cases by namespace (projectId)
 * - PUT    /api/cases/{id}             — update case metadata
 * - DELETE /api/cases/{id}             — soft-delete a case
 *
 * Endpoints (custom):
 * - POST   /api/cases                  — create a case and start its runtime instance
 * - POST   /api/cases/{caseId}/messages — add a message to a case
 * - POST   /api/cases/{caseId}/stop    — stop a case gracefully
 * - POST   /api/cases/{caseId}/kill    — kill a case immediately
 */
@RestController
@RequestMapping("/api/cases")
class CaseController(
    private val caseService: CaseService,
) : EntityController<CaseModel, UUID>(caseService) {
    /**
     * POST /api/cases/{caseId}/messages — add a user message to a running case.
     */
    @PostMapping("/{caseId}/messages")
    fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) = runBlocking {
        logger.info { "Adding message to case: $caseId" }

        val case =
            caseService.getCaseInstance(caseId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: $caseId")

        val actor = Actor(id = request.userId, displayName = request.userId, role = ActorRole.USER)
        val content = listOf(MessageContent.Text(request.content))

        case.addUserMessage(actor = actor, content = content, answerToEventId = request.answerToEventId)
        logger.info { "Message added to case: $caseId" }
    }

    /**
     * POST /api/cases/{caseId}/stop — stop a case gracefully.
     */
    @PostMapping("/{caseId}/stop")
    fun stopCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Stopping case: $caseId" }
        val stopped = caseService.stopCase(caseId)
        if (!stopped) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: $caseId")
        logger.info { "Case stopped: $caseId" }
    }

    /**
     * POST /api/cases/{caseId}/kill — kill a case immediately.
     */
    @PostMapping("/{caseId}/kill")
    fun killCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Killing case: $caseId" }
        val killed = caseService.killCase(caseId)
        if (!killed) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: $caseId")
        logger.info { "Case killed: $caseId" }
    }

    companion object : KLogging()
}

// ========================================
// Request DTOs
// ========================================

data class AddMessageRequest(
    val content: String,
    val userId: String = "default-user",
    val answerToEventId: UUID? = null,
)
