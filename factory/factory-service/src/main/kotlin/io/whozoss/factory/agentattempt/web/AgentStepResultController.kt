package io.whozoss.factory.agentattempt.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.agentattempt.domain.InvalidResultRequestException
import io.whozoss.factory.agentattempt.service.WorkerResultSubmissionService
import io.whozoss.factory.error.ErrorResponse
import io.whozoss.factory.web.AuthenticatedUser
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Result submission of a Factory worker, called through the standard AgentOS
 * `HTTP_API` integration (see `factory-worker-http-api-submit-step-result.yaml`):
 * `POST /api/factory/agent-step-results` with `{ attemptId, result }` and an
 * `Idempotency-Key` header.
 *
 * The controller only parses the request and delegates to
 * [WorkerResultSubmissionService]; the attempt, hence the workflow, the step and
 * the namespace, is resolved from the durable record, never from the caller.
 * Success uses the canonical `{ "data": ... }` envelope; failures are rendered by
 * the shared `FactoryExceptionHandler`.
 */
@RestController
@RequestMapping("/api/factory/agent-step-results")
@Tag(name = "agent-step-results", description = "Agent step result submission")
class AgentStepResultController(
    private val submissions: WorkerResultSubmissionService,
) {

    @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Submit the structured result of an agent step attempt.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Result created"),
        ApiResponse(responseCode = "200", description = "Idempotent replay of an identical submission"),
        ApiResponse(
            responseCode = "400",
            description = "RESULT_SCHEMA_INVALID or INVALID_RESULT_REQUEST",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "RESULT_SEMANTIC_COLLISION, IDEMPOTENCY_KEY_COLLISION or STALE_AMENDMENT_SEQUENCE",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun submit(
        @RequestBody(required = false) request: AgentStepResultRequest?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyHeader: String?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): ResponseEntity<AgentStepResultEnvelope<AgentStepResultData>> {
        val body = request ?: throw InvalidResultRequestException("A JSON body is required")
        if (body.observed != null || body.caseId != null || body.agentName != null ||
            body.business != null || body.idempotencyKey != null
        ) {
            throw InvalidResultRequestException(
                "This route accepts only 'attemptId', 'result', and an Idempotency-Key header",
            )
        }
        val attemptId = body.attemptId?.takeIf { it.isNotBlank() }
            ?: throw InvalidResultRequestException("'attemptId' is required")
        val result = foldAmendmentSeq(
            body.result ?: throw InvalidResultRequestException("'result' is required"),
            body.expected_amendment_seq ?: body.expectedAmendmentSeq,
        ) ?: throw InvalidResultRequestException("'result' is required")
        val idempotencyKey = idempotencyHeader?.takeIf { it.isNotBlank() }
            ?: throw InvalidResultRequestException("Idempotency-Key is required")
        if (idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw InvalidResultRequestException("'Idempotency-Key' must be at most $MAX_IDEMPOTENCY_KEY_LENGTH characters")
        }

        val submission = submissions.submit(user.organizationId, attemptId, result, idempotencyKey)
        val status = if (submission.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(
            AgentStepResultEnvelope(
                AgentStepResultData(submission.resultId, submission.idempotent, submission.resultHash),
            ),
        )
    }

    /**
     * Folds a top-level `expected_amendment_seq` into the business payload so the
     * amendment counter is part of the canonical, hashed result identity. An
     * explicit value already carried inside the result wins, so a divergent
     * top-level field is never silently rewritten.
     */
    private fun foldAmendmentSeq(business: JsonNode?, seq: Long?): JsonNode? {
        if (business == null || seq == null || !business.isObject) return business
        val node = (business as ObjectNode).deepCopy()
        if (!node.has("expected_amendment_seq") && !node.has("expectedAmendmentSeq")) {
            node.put("expected_amendment_seq", seq)
        }
        return node
    }

    private companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
    }
}
