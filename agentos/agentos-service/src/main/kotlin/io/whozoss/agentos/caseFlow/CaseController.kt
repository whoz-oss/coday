package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityController
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing Cases.
 *
 * Extends EntityController for standard CRUD on CaseModel (persistence layer).
 * Overrides create() to also initialize the runtime Case instance.
 * Adds lifecycle endpoints (messages, stop, kill) operating on the runtime Case instance.
 */
@RestController
@RequestMapping("/api/cases")
class CaseController(
    private val caseService: CaseService,
) : EntityController<CaseModel, UUID>(caseService) {

    /**
     * List all cases for a given project.
     *
     * GET /api/cases/by-project?projectId=xxx
     */
    @GetMapping("/by-project")
    fun listByProject(
        @RequestParam projectId: UUID,
    ): List<CaseModel> {
        logger.debug { "Listing cases for project: $projectId" }
        return listByParent(projectId)
    }

    /**
     * Create a new case and initialize its runtime instance.
     *
     * POST /api/cases
     * Body: CaseModel
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @RequestBody entity: CaseModel,
    ): CaseModel {
        logger.info("Creating new case for project: ${entity.projectId}")
        return caseService.save(entity)
    }

    /**
     * Add a message to a case runtime instance.
     *
     * POST /api/cases/:caseId/messages
     * Body: AddMessageRequest
     */
    @PostMapping("/{caseId}/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) {
        logger.info("Adding message to case: $caseId")
        caseService.addMessage(
            caseId = caseId,
            userId = request.userId,
            content = request.content,
            answerToEventId = request.answerToEventId,
        )
    }

    /**
     * Stop a case gracefully.
     *
     * POST /api/cases/:caseId/stop
     */
    @PostMapping("/{caseId}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun stopCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info("Stopping case: $caseId")

        if (!caseService.stopCase(caseId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: $caseId")
        }

        logger.info("Case stopped: $caseId")
    }

    /**
     * Kill a case immediately.
     *
     * POST /api/cases/:caseId/kill
     */
    @PostMapping("/{caseId}/kill")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun killCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info("Killing case: $caseId")

        if (!caseService.killCase(caseId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found: $caseId")
        }

        logger.info("Case killed: $caseId")
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
