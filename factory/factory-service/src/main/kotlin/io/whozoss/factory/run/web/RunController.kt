package io.whozoss.factory.run.web

import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.run.service.RunStartCommand
import io.whozoss.factory.web.AuthenticatedUser
import io.whozoss.factory.web.AuthenticationFilter
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.web.WorkflowDataEnvelope
import io.whozoss.factory.workflow.web.resolveWorkflowCaller
import io.whozoss.factory.workstream.WorkstreamService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST surface of parent Runs (Lot 1 & Lot 5, §8.1 & §8.7):
 * - `POST /api/factory/runs` — start a parent Run
 * - `GET /api/factory/runs` — list parent Runs in the caller's scope/namespace
 * - `GET /api/factory/runs/{runId}` — inspect a parent Run (fragment chain, domainState, findings, activeEscalation)
 * - `POST /api/factory/runs/{runId}/escalations/{escalationId}/reply` — answer a run-level human escalation
 */
@RestController
class RunController(
    private val runService: RunService,
    private val workstreamService: WorkstreamService,
    private val sessionRunService: SessionRunService? = null,
) {

    @PostMapping("/api/factory/runs")
    fun startRun(
        @RequestBody(required = false) body: Map<String, Any?>?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
        val payload = body ?: throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "Request body is required.")
        val namespaceId = payload["namespaceId"] as? String
        val caller = resolveWorkflowCaller(servletRequest.authenticatedUser(), namespaceId, workstreamService = workstreamService)
        val runId = (payload["runId"] as? String)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val runType = (payload["runType"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "runType is required.")
        val title = (payload["title"] as? String)?.takeIf { it.isNotBlank() } ?: "$runType ($runId)"
        val ticket = (payload["ticket"] as? String)?.takeIf { it.isNotBlank() }
        @Suppress("UNCHECKED_CAST")
        val initialDomainState = (payload["initialDomainState"] as? Map<String, Any?>).orEmpty()
        val repoRoot = (payload["repoRoot"] as? String)?.takeIf { it.isNotBlank() }

        val record = runService.startRun(
            scope = caller.scope,
            namespaceId = caller.namespaceId,
            command = RunStartCommand(
                runId = runId,
                runType = runType,
                title = title,
                ticket = ticket,
                initialDomainState = initialDomainState,
            ),
            repoRoot = repoRoot,
            actorId = caller.actorId,
        )
        return ResponseEntity.status(201).body(WorkflowDataEnvelope(record.toJson()))
    }

    @GetMapping("/api/factory/runs")
    fun listRuns(
        @RequestParam(required = false) namespaceId: String?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
        val caller = resolveWorkflowCaller(servletRequest.authenticatedUser(), namespaceId, workstreamService = workstreamService)
        val items = runService.listRuns(caller.scope, caller.namespaceId).map { it.toJson() }
        return ResponseEntity.ok(WorkflowDataEnvelope(mapOf("namespaceId" to caller.namespaceId, "items" to items)))
    }

    @GetMapping("/api/factory/runs/{runId}")
    fun getRun(
        @PathVariable runId: String,
        @RequestParam(required = false) namespaceId: String?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
        val caller = resolveWorkflowCaller(servletRequest.authenticatedUser(), namespaceId, workstreamService = workstreamService)
        val record = runService.getRun(caller.scope, caller.namespaceId, runId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND, "Run '$runId' not found.")
        return ResponseEntity.ok(WorkflowDataEnvelope(record.toJson()))
    }

    @PostMapping("/api/factory/runs/{runId}/escalations/{escalationId}/reply")
    fun replyEscalation(
        @PathVariable runId: String,
        @PathVariable escalationId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
        val payload = body ?: throw workflowException(WorkflowErrorCodes.INVALID_INTERACTION, "Request body is required.")
        val namespaceId = payload["namespaceId"] as? String
        val caller = resolveWorkflowCaller(servletRequest.authenticatedUser(), namespaceId, workstreamService = workstreamService)
        val selectedOption = (payload["selectedOption"] as? String)?.takeIf { it.isNotBlank() }
            ?: (payload["actionId"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_INTERACTION, "selectedOption is required.")
        val text = payload["text"] as? String

        val updated = runService.replyRunEscalation(
            scope = caller.scope,
            namespaceId = caller.namespaceId,
            runId = runId,
            escalationId = escalationId,
            selectedOption = selectedOption,
            text = text,
            actorId = caller.actorId,
            sessionRunService = sessionRunService,
        )
        return ResponseEntity.ok(WorkflowDataEnvelope(updated.toJson()))
    }

    private fun HttpServletRequest.authenticatedUser(): AuthenticatedUser =
        getAttribute(AuthenticationFilter.ATTRIBUTE) as AuthenticatedUser

    private fun RunRecord.toJson(): Map<String, Any?> = linkedMapOf(
        "runId" to runId,
        "namespaceId" to namespaceId,
        "runType" to runType,
        "selectorVersion" to selectorVersion,
        "title" to title,
        "ticket" to ticket,
        "status" to status,
        "revision" to revision,
        "rootCaseId" to rootCaseId,
        "domainRevision" to domainRevision,
        "amendmentSeq" to amendmentSeq,
        "domainState" to domainState,
        "findings" to findings,
        "fragments" to fragments,
        "loopCounts" to loopCounts,
        "activeEscalation" to activeEscalation,
    )
}
