package io.biznet.agentos.api

import io.biznet.agentos.orchestration.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * REST API for managing Cases.
 *
 * Provides CRUD operations and lifecycle management for cases.
 * Cases are conversations that execute agents to process user requests.
 */
@RestController
@RequestMapping("/api/cases")
class CaseController(
    private val caseService: CaseService,
) {
    private val logger = LoggerFactory.getLogger(CaseController::class.java)

    /**
     * Create a new case.
     *
     * POST /api/cases
     * Body: CreateCaseRequest
     */
    @PostMapping
    fun createCase(
        @RequestBody request: CreateCaseRequest,
    ): ResponseEntity<CaseResponse> {
        logger.info("Creating new case for project: ${request.projectId}")

        val case =
            caseService.createCaseInstance(
                projectId = request.projectId,
                initialEvents = emptyList(),
            )

        logger.info("Case created: ${case.id}")

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(case.toResponse())
    }

    /**
     * Get a case by ID.
     *
     * GET /api/cases/:caseId
     */
    @GetMapping("/{caseId}")
    fun getCase(
        @PathVariable caseId: UUID,
    ): ResponseEntity<CaseResponse> {
        logger.debug("Getting case: $caseId")

        val case = caseService.getCaseInstance(caseId)
        if (case == null) {
            logger.warn("Case not found: $caseId")
            return ResponseEntity.notFound().build()
        }

        return ResponseEntity.ok(case.toResponse())
    }

    /**
     * List cases with optional filters.
     *
     * GET /api/cases?projectId=xxx
     *
     * Note: Only returns active (running) cases.
     */
    @GetMapping
    fun listCases(
        @RequestParam(required = false) projectId: UUID?,
    ): ResponseEntity<List<CaseResponse>> {
        logger.debug("Listing cases - projectId: $projectId")

        val cases =
            if (projectId != null) {
                caseService.getActiveCasesByProject(projectId)
            } else {
                caseService.getAllActiveCases()
            }

        return ResponseEntity.ok(cases.map { it.toResponse() })
    }

    /**
     * Update a case.
     *
     * PUT /api/cases/:caseId
     * Body: UpdateCaseRequest
     *
     * Note: Currently not implemented. Use dedicated endpoints (stop, kill) for lifecycle management.
     */
    @PutMapping("/{caseId}")
    fun updateCase(
        @PathVariable caseId: UUID,
        @RequestBody request: UpdateCaseRequest,
    ): ResponseEntity<CaseResponse> {
        logger.info("Updating case: $caseId")

        val case = caseService.getCaseInstance(caseId)
        if (case == null) {
            logger.warn("Case not found: $caseId")
            return ResponseEntity.notFound().build()
        }

        // For now, we only support lifecycle updates via dedicated endpoints (stop, kill)
        // This endpoint is a placeholder for future metadata updates
        logger.warn("Update case endpoint called but not implemented yet")

        return ResponseEntity.ok(case.toResponse())
    }

    /**
     * Delete a case.
     *
     * DELETE /api/cases/:caseId
     */
    @DeleteMapping("/{caseId}")
    fun deleteCase(
        @PathVariable caseId: UUID,
    ): ResponseEntity<Void> {
        logger.info("Deleting case: $caseId")

        val deleted = caseService.deleteMany(listOf(caseId))

        return if (deleted > 0) {
            logger.info("Case deleted: $caseId")
            ResponseEntity.noContent().build()
        } else {
            logger.warn("Case not found: $caseId")
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Add a message to a case.
     *
     * POST /api/cases/:caseId/messages
     * Body: AddMessageRequest
     */
    @PostMapping("/{caseId}/messages")
    fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ): ResponseEntity<Void> =
        runBlocking {
            logger.info("Adding message to case: $caseId")
            logger.debug("Message: ${request.content}")

            val case = caseService.getCaseInstance(caseId)
            if (case == null) {
                logger.warn("Case not found: $caseId")
                return@runBlocking ResponseEntity.notFound().build()
            }

            val actor =
                Actor(
                    id = request.userId,
                    displayName = request.userId,
                    role = ActorRole.USER,
                )

            val content = listOf(MessageContent.Text(request.content))

            case.addUserMessage(
                actor = actor,
                content = content,
                answerToEventId = request.answerToEventId,
            )

            logger.info("Message added to case: $caseId")
            ResponseEntity.ok().build()
        }

    /**
     * Stop a case gracefully.
     *
     * POST /api/cases/:caseId/stop
     */
    @PostMapping("/{caseId}/stop")
    fun stopCase(
        @PathVariable caseId: UUID,
    ): ResponseEntity<Void> {
        logger.info("Stopping case: $caseId")

        val stopped = caseService.stopCase(caseId)

        return if (stopped) {
            logger.info("Case stopped: $caseId")
            ResponseEntity.ok().build()
        } else {
            logger.warn("Case not found: $caseId")
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Kill a case immediately.
     *
     * POST /api/cases/:caseId/kill
     */
    @PostMapping("/{caseId}/kill")
    fun killCase(
        @PathVariable caseId: UUID,
    ): ResponseEntity<Void> {
        logger.info("Killing case: $caseId")

        val killed = caseService.killCase(caseId)

        return if (killed) {
            logger.info("Case killed: $caseId")
            ResponseEntity.ok().build()
        } else {
            logger.warn("Case not found: $caseId")
            ResponseEntity.notFound().build()
        }
    }
}

// ========================================
// Request/Response DTOs
// ========================================

data class CreateCaseRequest(
    val projectId: UUID,
)

data class UpdateCaseRequest(
    // Placeholder for future metadata updates
    val dummy: String? = null,
)

data class AddMessageRequest(
    val content: String,
    val userId: String = "default-user",
    val answerToEventId: UUID? = null,
)

data class CaseResponse(
    val id: UUID,
    val projectId: UUID,
)

// Extension function to convert Case to CaseResponse
private fun Case.toResponse(): CaseResponse =
    CaseResponse(
        id = id,
        projectId = projectId,
    )
