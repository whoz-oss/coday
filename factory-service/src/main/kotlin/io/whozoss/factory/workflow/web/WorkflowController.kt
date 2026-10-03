package io.whozoss.factory.workflow.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.agentattempt.domain.DurableAgentAttemptDto
import io.whozoss.factory.agentattempt.domain.toDto
import io.whozoss.factory.agentattempt.service.BridgeCancellationService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.config.SessionProperties
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.ControllerRequestInput
import io.whozoss.factory.workflow.domain.WorkflowActionsResponseDto
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowExecution
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workflow.service.WorkflowHttpResult
import io.whozoss.factory.workflow.service.WorkflowService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Canonical HTTP surface of the workflow aggregate.
 *
 * Port of the Node dashboard route handlers
 * (`workflow-projection-routes.mjs`, `workflow-transition-routes.mjs`,
 * `workflow-code-transition-routes.mjs`, `workflow-evidence-routes.mjs`,
 * `workflow-human-interaction-routes.mjs`,
 * `workflow-operational-metrics-routes.mjs`, `factory-frontend-run-routes.mjs`).
 *
 * Success responses use the `{ "data": ... }` envelope; failures are rendered by
 * the shared `FactoryExceptionHandler` as `{ "error": { code, message } }`.
 * Identity is resolved from the verified [TrustContext]; a missing one fails
 * closed with `401 TRUST_CONTEXT_UNAVAILABLE`.
 */
@RestController
@RequestMapping("/api/factory/workflows")
@Tag(name = "workflows", description = "Workflow projections, transitions and interactions")
class WorkflowController(
    private val service: WorkflowService,
    private val sessionRunService: SessionRunService,
    private val sessionRunSubmissionService: SessionRunSubmissionService,
    private val sessionProperties: SessionProperties,
    private val tenantScopeProvider: TenantScopeProvider,
    private val agentOsProxyClient: AgentOsProxyClient,
    /**
     * Read side of the durable execution attempts, used by the Cockpit V2
     * attempts listing route.
     */
    private val durableAgentAttemptService: DurableAgentAttemptService,
    /**
     * Optional bridge cancellation command. Present only when the AgentOS
     * execution adapter is enabled; the cancellation route reports a clean 503
     * otherwise.
     */
    private val bridgeCancellationService: BridgeCancellationService? = null,
) {

    // ----- collection / detail ------------------------------------------

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "List workflow projections of the caller's tenant scope (optionally filtered by namespace).")
    fun list(
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestParam(name = "state", required = false) state: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        // `namespaceId` is an OPTIONAL filter: absent/blank lists every namespace
        // of the caller's trusted tenant scope.
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId, requireNamespace = false)
        return WorkflowDataEnvelope(service.listProjections(caller.scope, caller.namespaceId, state ?: "active"))
    }

    @GetMapping(path = ["/{workflowId}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Read a workflow projection or its lifecycle state.")
    fun detail(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(service.getProjection(caller.scope, caller.namespaceId, workflowId))
    }

    @DeleteMapping(path = ["/{workflowId}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Remove a workflow projection (recoverable).")
    @Suppress("UNCHECKED_CAST")
    fun remove(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(service.remove(caller.scope, caller.namespaceId, workflowId).data as Map<String, Any?>)
    }

    // ----- projection ----------------------------------------------------

    @GetMapping(path = ["/{workflowId}/projection"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Read the current workflow projection.")
    fun projection(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(service.getProjection(caller.scope, caller.namespaceId, workflowId))
    }

    @PutMapping(path = ["/{workflowId}/projection"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Publish a declarative workflow projection.")
    fun publishProjection(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> {
        val request = requireBody(body)
        val allowed = setOf("projection", "execution", "expectedRevision")
        if (request.keys.any { it !in allowed }) {
            throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "Request body must contain projection and execution.")
        }
        val execution = requireExecution(request["execution"] as? Map<*, *>)
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, execution.namespaceId)
        val expectedRevision = (request["expectedRevision"] as? Number)?.toInt()
        return respond(
            service.publishProjection(
                caller.scope,
                caller.namespaceId,
                workflowId,
                request["projection"],
                expectedRevision,
                execution.toJson(),
            ),
        )
    }

    // ----- start ---------------------------------------------------------

    @PostMapping(path = ["/{workflowId}/start"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Start a governed workflow instance from a definition.")
    fun start(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> = startInternal(workflowId, body, trustContext)

    @PostMapping(path = ["/start"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Start a governed workflow instance (collection form).")
    fun startCollection(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> {
        val request = requireBody(body)
        val workflow = request["workflow"] as? Map<*, *>
            ?: throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST)
        val workflowId = workflow["workflowId"] as? String
            ?: throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST)
        return startInternal(workflowId, body, trustContext)
    }

    private fun startInternal(
        workflowId: String,
        body: Map<String, Any?>?,
        trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> {
        val request = requireBody(body)
        if (request.keys.any { it !in setOf("workflow", "execution", "controllerRequest") }) {
            throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "Request body must contain workflow, execution and controllerRequest.")
        }
        val workflow = (request["workflow"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST)
        if (workflow.keys.any { it !in setOf("workflowId", "workflowType", "title", "relations", "ticket") } ||
            workflow["workflowId"] != workflowId ||
            workflow["workflowType"] !is String ||
            (workflow["title"] as? String).isNullOrBlank()
        ) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST)
        }
        val execution = requireExecution(request["execution"] as? Map<*, *>)
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, execution.namespaceId)
        // The optional ticket may be carried by the workflow object (preferred) or
        // the top-level request. It is mirrored into `relations` so the branch
        // naming / session context can read it from the persisted instance.
        val ticket = ((workflow["ticket"] as? String) ?: (request["ticket"] as? String))?.takeIf { it.isNotBlank() }
        val explicitRelations = (workflow["relations"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        val relations: Map<String, Any?>? = when {
            explicitRelations != null && ticket != null -> explicitRelations + ("ticket" to ticket)
            explicitRelations != null -> explicitRelations
            ticket != null -> mapOf("rootWorkflowId" to workflowId, "ticket" to ticket)
            else -> null
        }
        val requestText = (request["controllerRequest"] as? String)?.trim()
            ?: if (isFactoryCockpitExecution(execution)) {
                throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "controllerRequest is required.")
            } else {
                null
            }
        if (requestText != null && (requestText.isEmpty() || requestText.length > CONTROLLER_REQUEST_MAX)) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "controllerRequest must contain 1 to $CONTROLLER_REQUEST_MAX characters.")
        }
        val command = WorkflowStartCommand(
            workflowId = workflowId,
            workflowType = workflow["workflowType"] as String,
            title = workflow["title"] as String,
            relations = relations,
            ticket = ticket,
            controllerRequest = requestText?.let {
                ControllerRequestInput(
                    text = it,
                    namespaceId = caller.namespaceId,
                    observedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                    actorId = caller.actorId,
                    source = CONTROLLER_REQUEST_SOURCE,
                )
            },
        )
        return respond(service.start(caller.scope, caller.namespaceId, command, execution))
    }

    // ----- transitions ---------------------------------------------------

    @PostMapping(path = ["/{workflowId}/transitions"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Apply a revision-safe workflow transition.")
    fun transition(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Any?> = transitionInternal(workflowId, body, trustContext, code = false)

    @PostMapping(path = ["/transitions"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Apply a revision-safe workflow transition (collection form).")
    fun transitionCollection(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Any?> {
        val request = requireBody(body)
        val workflowId = transitionWorkflowId(request)
        return transitionInternal(workflowId, body, trustContext, code = false)
    }

    @PostMapping(path = ["/{workflowId}/code-transitions"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Apply a deterministic code transition.")
    fun codeTransition(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Any?> = transitionInternal(workflowId, body, trustContext, code = true)

    @PostMapping(path = ["/code-transitions"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Apply a deterministic code transition (collection form).")
    fun codeTransitionCollection(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Any?> {
        val request = requireBody(body)
        val workflowId = transitionWorkflowId(request)
        return transitionInternal(workflowId, body, trustContext, code = true)
    }

    private fun transitionInternal(
        workflowId: String,
        body: Map<String, Any?>?,
        trustContext: TrustContext?,
        code: Boolean,
    ): WorkflowDataEnvelope<Any?> {
        val request = requireBody(body)
        if (code) {
            if (request.keys.any { it != "transition" }) {
                throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "Only transition is accepted.")
            }
        } else if (request.keys.any { it !in setOf("transition", "execution") }) {
            throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "Request body must contain transition and execution.")
        }
        val caller = resolveWorkflowCaller(
            trustContext,
            tenantScopeProvider,
            (request["execution"] as? Map<*, *>)?.get("namespaceId") as? String,
        )
        val result = if (code) {
            service.codeTransition(caller.scope, caller.namespaceId, workflowId, request["transition"])
        } else {
            val execution = executionFrom(request["execution"] as? Map<*, *>, caller.namespaceId)
            service.transition(caller.scope, caller.namespaceId, workflowId, request["transition"], execution)
        }
        return WorkflowDataEnvelope(result.data)
    }

    private fun transitionWorkflowId(request: Map<String, Any?>): String {
        val transition = request["transition"] as? Map<*, *>
        return transition?.get("workflowId") as? String
            ?: throw workflowException(WorkflowErrorCodes.INVALID_TRANSITION_REQUEST)
    }

    // ----- run / continue / retries --------------------------------------

    @PostMapping(path = ["/{workflowId}/run"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Run the frontend-controlled workflow.")
    fun run(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @RequestParam(name = "sync", required = false) sync: Boolean?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> = runInternal(workflowId, body, trustContext, "run", sync ?: false)

    @PostMapping(path = ["/{workflowId}/continue"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Continue a paused workflow.")
    fun continueWorkflow(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @RequestParam(name = "sync", required = false) sync: Boolean?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> = runInternal(workflowId, body, trustContext, "continue", sync ?: false)

    private fun runInternal(
        workflowId: String,
        body: Map<String, Any?>?,
        trustContext: TrustContext?,
        operation: String,
        sync: Boolean,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> {
        val request = requireBody(body)
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, request["namespaceId"] as? String)
        val projection = service.getProjection(caller.scope, caller.namespaceId, workflowId)
        if (projection["state"] != "existing") {
            throw workflowException(
                if (projection["state"] == "absent") WorkflowErrorCodes.WORKFLOW_NOT_FOUND else WorkflowErrorCodes.WORKFLOW_REMOVED,
            )
        }
        val repoRoot = (request["repoRoot"] as? String)?.takeIf { it.isNotBlank() }
            ?: resolveRepoRoot(caller.namespaceId, trustContext?.principalId)
            ?: sessionProperties.defaultRepoRoot?.takeIf { it.isNotBlank() }
            ?: throw workflowException(
                WorkflowErrorCodes.INVALID_REQUEST,
                "The namespace has no resolvable repository root and no explicit/default repoRoot was provided.",
            )
        val ticket = (request["ticket"] as? String)?.takeIf { it.isNotBlank() }
        if (!sync) {
            // Asynchronous, durable submission: enqueue the run and answer 202 with
            // the tracking identity. The bounded outbox worker drains it, so the
            // HTTP connection is never held for the agent turn and an undrained
            // submission survives a restart.
            val submissionId = sessionRunSubmissionService.submit(
                caller.scope,
                caller.namespaceId,
                workflowId,
                repoRoot,
                operation,
                ticket,
            )
            return ResponseEntity.accepted().body(
                WorkflowDataEnvelope(
                    mapOf(
                        "workflowId" to workflowId,
                        "namespaceId" to caller.namespaceId,
                        "operation" to operation,
                        "status" to "accepted",
                        "submissionId" to submissionId,
                        "runtimeNotification" to "durable-outbox",
                    ),
                ),
            )
        }
        return ResponseEntity.ok(
            WorkflowDataEnvelope(
                runSession(caller.scope, caller.namespaceId, workflowId, Paths.get(repoRoot), operation, ticket),
            ),
        )
    }

    /**
     * Resolves the target repository from AgentOS' trusted namespace configPath.
     * The namespace points to its Coday configuration directory; the proxy
     * adapter returns its parent repository root.
     */
    private fun resolveRepoRoot(namespaceId: String, externalUserId: String?): String? =
        runCatching { agentOsProxyClient.resolveRepoRoot(namespaceId, externalUserId) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun runSession(
        scope: io.whozoss.factory.persistence.TenantScope,
        namespaceId: String,
        workflowId: String,
        repoRoot: Path,
        operation: String,
        ticket: String?,
    ): Map<String, Any?> {
        val result = sessionRunService.runSession(scope, namespaceId, workflowId, repoRoot, ticket)
        return mapOf(
            "workflowId" to result.workflowId,
            "namespaceId" to result.namespaceId,
            "operation" to operation,
            "status" to result.status,
            "steps" to result.steps.map { mapOf("id" to it.stepId, "status" to it.status) },
            "runtimeNotification" to "not-configured",
        )
    }

    // ----- attempts / explicit cancellation ------------------------------

    /**
     * List every durable execution attempt of a workflow (Cockpit V2).
     *
     * Returns a bounded, secret-free read model per attempt. An unknown or
     * attempt-less workflow degrades cleanly to an empty `{ "data": [] }` with
     * HTTP 200 (never an error).
     */
    @GetMapping(path = ["/{workflowId}/attempts"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "List durable execution attempts of a workflow.")
    fun listAttempts(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<List<DurableAgentAttemptDto>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        val attempts = durableAgentAttemptService.findByWorkflow(caller.scope, caller.namespaceId, workflowId)
        return WorkflowDataEnvelope(attempts.map { it.toDto() })
    }

    /**
     * Explicit business cancellation of a durable agent attempt.
     *
     * Closing the SSE stream (or a browser tab) only stops *observing* the run;
     * it never cancels it. Cancellation requires this explicit, revision-fenced
     * command, which interrupts/kills the AgentOS case, reconciles its post-kill
     * state and moves the attempt to the durable terminal `interrupted` status.
     */
    @PostMapping(path = ["/{workflowId}/attempts/{attemptId}/cancel"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Explicitly cancel a durable agent attempt (revision-fenced, interrupt/kill + reconcile).")
    fun cancelAttempt(
        @PathVariable workflowId: String,
        @PathVariable attemptId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val request = requireBody(body)
        if (request.keys.any { it !in setOf("namespaceId", "expectedRevision", "reason") }) {
            throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "Request body must contain expectedRevision.")
        }
        val expectedRevision = (request["expectedRevision"] as? Number)?.toInt()
            ?: throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "expectedRevision is required.")
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, request["namespaceId"] as? String)
        val cancellation = bridgeCancellationService
            ?: throw WorkflowException(
                "BRIDGE_CANCELLATION_UNAVAILABLE",
                "The AgentOS execution bridge is not enabled; explicit cancellation is unavailable.",
                503,
            )
        val reason = (request["reason"] as? String)?.takeIf { it.isNotBlank() } ?: BridgeCancellationService.DEFAULT_REASON
        val outcome = cancellation.requestCancel(
            caller.scope,
            caller.namespaceId,
            workflowId,
            attemptId,
            expectedRevision,
            reason,
        )
        return WorkflowDataEnvelope(
            mapOf(
                "workflowId" to outcome.workflowId,
                "attemptId" to outcome.attemptId,
                "stepId" to outcome.stepId,
                "status" to outcome.status.dbValue,
                "revision" to outcome.revision,
                "idempotent" to outcome.idempotent,
                "reconciledVerdict" to outcome.reconciledVerdict,
            ),
        )
    }

    @GetMapping(path = ["/{workflowId}/session"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Read the session DAG state (per-step statuses + overall status).")
    fun sessionState(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        val result = sessionRunService.sessionState(caller.scope, caller.namespaceId, workflowId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        return WorkflowDataEnvelope(
            mapOf(
                "workflowId" to result.workflowId,
                "namespaceId" to result.namespaceId,
                "status" to result.status,
                "steps" to result.steps.map { mapOf("id" to it.stepId, "status" to it.status) },
            ),
        )
    }

    @PostMapping(path = ["/{workflowId}/retries"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Open a retry for a blocked step.")
    fun retries(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> {
        val request = requireBody(body)
        val allowed = setOf("namespaceId", "stepId", "expectedRevision", "reasonCode")
        val stepId = request["stepId"] as? String
        val expectedRevision = (request["expectedRevision"] as? Number)?.toInt()
        val reasonCode = request["reasonCode"] as? String
        if (request.keys.any { it !in allowed } || stepId == null || expectedRevision == null || reasonCode == null) {
            throw workflowException("INVALID_RETRY_REQUEST", "Retry request is invalid.")
        }
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, request["namespaceId"] as? String)
        return respond(service.openRetry(caller.scope, caller.namespaceId, workflowId, stepId, expectedRevision, reasonCode))
    }

    // ----- lifecycle -----------------------------------------------------

    @PostMapping(path = ["/{workflowId}/restore"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Restore a removed workflow projection.")
    @Suppress("UNCHECKED_CAST")
    fun restore(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(service.restore(caller.scope, caller.namespaceId, workflowId).data as Map<String, Any?>)
    }

    @PostMapping(path = ["/{workflowId}/purge"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Purge a removed workflow projection.")
    fun purgePost(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> = purgeInternal(workflowId, namespaceId, trustContext)

    @DeleteMapping(path = ["/{workflowId}/purge"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Purge a removed workflow projection (DELETE form).")
    fun purgeDelete(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> = purgeInternal(workflowId, namespaceId, trustContext)

    @Suppress("UNCHECKED_CAST")
    private fun purgeInternal(
        workflowId: String,
        namespaceId: String?,
        trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(service.purge(caller.scope, caller.namespaceId, workflowId).data as Map<String, Any?>)
    }

    // ----- metrics -------------------------------------------------------

    @GetMapping(path = ["/{workflowId}/timing"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Read workflow timing aggregates.")
    fun timing(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(service.timing(caller.scope, caller.namespaceId, workflowId))
    }

    @GetMapping(path = ["/{workflowId}/retries"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Read workflow retry aggregates.")
    fun retriesMetrics(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(service.retries(caller.scope, caller.namespaceId, workflowId))
    }

    @GetMapping(path = ["/{workflowId}/metrics"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Per-workflow operational metrics.")
    fun metrics(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestParam(name = "scope", required = false) scope: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        val resolvedScope = scope ?: "self"
        if (resolvedScope !in setOf("self", "descendants")) {
            throw workflowException("INVALID_METRICS_SCOPE", "scope must be self or descendants.")
        }
        return WorkflowDataEnvelope(service.metrics(caller.scope, caller.namespaceId, workflowId, resolvedScope))
    }

    // ----- evidence ------------------------------------------------------

    @GetMapping(path = ["/{workflowId}/evidence"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "List workflow evidence.")
    fun listEvidence(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestParam(name = "stepId", required = false) stepId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Any?> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(service.listEvidence(caller.scope, caller.namespaceId, workflowId, stepId).data)
    }

    @PostMapping(path = ["/{workflowId}/evidence"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Append an audited workflow evidence fact.")
    fun appendEvidence(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> {
        val request = requireBody(body)
        if (request.keys.any { it !in setOf("evidence", "execution") }) {
            throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "Request body must contain evidence and execution.")
        }
        val execution = requireExecution(request["execution"] as? Map<*, *>)
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, execution.namespaceId)
        val evidence = parseEvidence(request["evidence"], workflowId, execution.namespaceId!!)
        return respond(service.appendEvidence(caller.scope, caller.namespaceId, workflowId, evidence))
    }

    // ----- interactions --------------------------------------------------

    @PostMapping(path = ["/{workflowId}/agent-questions/{questionEventId}/answer"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Submit an authenticated answer to the active AgentOS question.")
    fun answerAgentQuestion(
        @PathVariable workflowId: String,
        @PathVariable questionEventId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> {
        val request = requireBody(body)
        if (request.keys.any { it !in setOf("namespaceId", "stepId", "answer") }) {
            throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "Only namespaceId, stepId and answer are accepted.")
        }
        val stepId = (request["stepId"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "stepId is required.")
        val answer = request["answer"] as? String
            ?: throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "answer is required.")
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, request["namespaceId"] as? String)
        if (!trustContext?.authenticated.orFalse() || trustContext?.principalType != TrustContext.PRINCIPAL_TYPE_HUMAN || !isSafeActor(caller.actorId)) {
            throw workflowException(WorkflowErrorCodes.UNAUTHENTICATED_ACTOR)
        }
        return respond(
            service.submitAgentQuestionAnswer(
                caller.scope,
                caller.namespaceId,
                workflowId,
                stepId,
                questionEventId,
                answer,
                caller.actorId,
            ),
        )
    }

    @GetMapping(path = ["/{workflowId}/interactions"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "List human interactions for a workflow.")
    fun listInteractions(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @RequestParam(name = "state", required = false) state: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Any?> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        return WorkflowDataEnvelope(
            service.listInteractions(caller.scope, caller.namespaceId, workflowId, openOnly = state != "all").data,
        )
    }

    @PostMapping(path = ["/{workflowId}/interactions"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Open a human interaction at an exact workflow revision.")
    fun openInteraction(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Any?>> {
        val request = requireBody(body)
        val allowed = setOf("stepId", "expectedRevision", "prompt", "actions", "idempotencyKey")
        val stepId = request["stepId"] as? String
        val expectedRevision = (request["expectedRevision"] as? Number)?.toInt()
        val prompt = request["prompt"] as? String
        val idempotencyKey = request["idempotencyKey"] as? String
        val actions = request["actions"] as? List<*>
        if (request.keys.any { it !in allowed } || stepId == null || expectedRevision == null ||
            prompt == null || prompt.isEmpty() || prompt.length > 2000 ||
            idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length > 128 ||
            actions == null || actions.size != 2
        ) {
            throw workflowException(WorkflowErrorCodes.INVALID_INTERACTION)
        }
        val normalizedActions = actions.mapNotNull { entry ->
            val action = (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return@mapNotNull null
            if (action.keys.any { it !in setOf("id", "label") } ||
                action["id"] !in setOf("approve", "reject") ||
                (action["label"] as? String).isNullOrBlank()
            ) {
                throw workflowException(WorkflowErrorCodes.INVALID_INTERACTION)
            }
            action
        }
        if (normalizedActions.size != 2 || normalizedActions.map { it["id"] }.distinct().size != 2) {
            throw workflowException(WorkflowErrorCodes.INVALID_INTERACTION)
        }
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider)
        return respond(
            service.openInteraction(
                caller.scope,
                caller.namespaceId,
                workflowId,
                stepId,
                expectedRevision,
                prompt,
                normalizedActions,
                idempotencyKey,
            ),
        )
    }

    @PostMapping(path = ["/{workflowId}/interactions/{interactionId}/reply"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Reply to a human interaction atomically.")
    fun replyInteraction(
        @PathVariable workflowId: String,
        @PathVariable interactionId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Any?> {
        val request = requireBody(body)
        val allowed = setOf("expectedRevision", "actionId", "text")
        val expectedRevision = (request["expectedRevision"] as? Number)?.toInt()
        val actionId = request["actionId"] as? String
        val text = request["text"] as? String
        if (request.keys.any { it !in allowed } || expectedRevision == null || actionId.isNullOrEmpty() ||
            (text != null && text.length > 2000)
        ) {
            throw workflowException(WorkflowErrorCodes.INVALID_REPLY)
        }
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider)
        if (!isSafeActor(caller.actorId)) throw workflowException(WorkflowErrorCodes.UNAUTHENTICATED_ACTOR)
        return WorkflowDataEnvelope(
            service.replyInteraction(
                caller.scope,
                caller.namespaceId,
                workflowId,
                interactionId,
                expectedRevision,
                actionId,
                text,
                caller.actorId,
            ).data,
        )
    }

    // ----- governed actions & cost control -------------------------------

    /**
     * Authoritative read of what a governed workflow permits right now: the
     * allowed actions (each with the revision it must be executed at) and the
     * active blockers. Derived purely from persisted state + the trusted caller
     * identity — the client never decides this itself.
     */
    @GetMapping(path = ["/{workflowId}/actions"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Authoritative allowed actions and blockers of a workflow.")
    fun actions(
        @PathVariable workflowId: String,
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<WorkflowActionsResponseDto> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)
        val canReply = trustContext?.principalType == TrustContext.PRINCIPAL_TYPE_HUMAN && isSafeActor(caller.actorId)
        return WorkflowDataEnvelope(service.workflowActions(caller.scope, caller.namespaceId, workflowId, canReply))
    }

    @PostMapping(path = ["/{workflowId}/cost/continue"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Relay a run-cost continuation to AgentOS for the workflow's cases.")
    fun continueCost(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @RequestParam(name = "namespaceId", required = false) queryNamespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> =
        costControl(workflowId, body, queryNamespaceId, trustContext, continueRun = true)

    @PostMapping(path = ["/{workflowId}/cost/stop"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Relay a run-cost stop to AgentOS for the workflow's cases.")
    fun stopCost(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @RequestParam(name = "namespaceId", required = false) queryNamespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> =
        costControl(workflowId, body, queryNamespaceId, trustContext, continueRun = false)

    /**
     * Shared cost-control pass-through. Identity and namespace are resolved from
     * the verified [TrustContext] (and the optional `namespaceId` query param)
     * first; the persisted workflow state is authoritative for the target case
     * ids. A body `namespaceId` is only a last-resort hint when the trusted
     * context carries no namespace at all.
     */
    private fun costControl(
        workflowId: String,
        body: Map<String, Any?>?,
        queryNamespaceId: String?,
        trustContext: TrustContext?,
        continueRun: Boolean,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val request = body ?: emptyMap()
        val allowed = setOf("expectedThreshold", "caseId", "namespaceId", "expectedRevision")
        if (request.keys.any { it !in allowed }) {
            throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "Unsupported cost-control request fields.")
        }
        val trustedNamespaceId = queryNamespaceId?.takeIf { it.isNotBlank() }
            ?: trustContext?.namespaceId?.takeIf { it.isNotBlank() }
        val bodyNamespaceId = (request["namespaceId"] as? String)?.takeIf { it.isNotBlank() }
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, trustedNamespaceId ?: bodyNamespaceId)
        val expectedThreshold = (request["expectedThreshold"] as? Number)?.toDouble()
        val caseId = (request["caseId"] as? String)?.takeIf { it.isNotBlank() }
        val expectedRevision = (request["expectedRevision"] as? Number)?.toInt()
        val result = if (continueRun) {
            service.continueRunCost(
                caller.scope,
                caller.namespaceId,
                workflowId,
                caller.actorId,
                caseId,
                expectedThreshold,
                expectedRevision,
            )
        } else {
            service.stopRunCost(
                caller.scope,
                caller.namespaceId,
                workflowId,
                caller.actorId,
                caseId,
                expectedRevision,
            )
        }
        return WorkflowDataEnvelope(result)
    }

    // ----- helpers -------------------------------------------------------

    private fun requireBody(body: Map<String, Any?>?): Map<String, Any?> =
        body ?: throw workflowException(WorkflowErrorCodes.INVALID_REQUEST)

    private fun requireExecution(raw: Map<*, *>?): ControllerExecutionInput {
        val execution = raw?.entries?.associate { it.key.toString() to it.value }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_EXECUTION)
        val namespaceId = execution["namespaceId"] as? String
        if (!isValidNamespaceId(namespaceId)) {
            throw workflowException(WorkflowErrorCodes.INVALID_NAMESPACE_ID)
        }
        val runtimeId = execution["runtimeId"] as? String
        val kind = execution["kind"] as? String
        val agentId = execution["agentId"] as? String
        if (runtimeId.isNullOrBlank() || agentId.isNullOrBlank() || kind == null ||
            kind !in setOf("agentos", "coday-express")
        ) {
            throw workflowException(WorkflowErrorCodes.INVALID_EXECUTION)
        }
        return ControllerExecutionInput(
            runtimeId = runtimeId,
            kind = kind,
            agentId = agentId,
            caseId = execution["caseId"] as? String,
            actorId = execution["actorId"] as? String,
            threadId = execution["threadId"] as? String,
            namespaceId = namespaceId,
        )
    }

    private fun executionFrom(raw: Map<*, *>?, namespaceId: String): WorkflowExecution {
        val execution = raw?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
        return WorkflowExecution(
            kind = execution["kind"] as? String ?: "factory-control-plane",
            runtimeId = execution["runtimeId"] as? String ?: "factory-dashboard",
            agentId = execution["agentId"] as? String,
            actorId = execution["actorId"] as? String,
            caseId = execution["caseId"] as? String,
            threadId = execution["threadId"] as? String,
            namespaceId = namespaceId,
        )
    }

    private fun parseEvidence(raw: Any?, workflowId: String, namespaceId: String): io.whozoss.factory.workflow.domain.WorkflowEvidenceItem {
        val evidence = (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_EVIDENCE)
        val allowed = setOf("workflowId", "stepId", "kind", "outcome", "facts", "idempotencyKey")
        if (evidence.keys.any { it !in allowed } || evidence["workflowId"] != workflowId) {
            throw workflowException(WorkflowErrorCodes.INVALID_EVIDENCE)
        }
        val kind = evidence["kind"] as? String
        if (kind.isNullOrBlank()) throw workflowException(WorkflowErrorCodes.INVALID_EVIDENCE)
        if (kind in setOf("oracle-result", "human-decision")) {
            throw workflowException(
                WorkflowErrorCodes.FACTORY_ONLY_EVIDENCE,
                "$kind evidence is produced only by a trusted Factory control-plane route.",
            )
        }
        val stepId = evidence["stepId"] as? String
        val outcome = evidence["outcome"] as? String
        return io.whozoss.factory.workflow.domain.WorkflowEvidenceItem(
            evidenceId = UUID.randomUUID().toString(),
            namespaceId = namespaceId,
            workflowId = workflowId,
            stepId = stepId,
            kind = kind,
            outcome = outcome,
            source = mapOf("kind" to "factory-control-plane", "runtimeId" to "factory-dashboard"),
            facts = (evidence["facts"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap(),
            idempotencyKey = evidence["idempotencyKey"] as? String,
            createdAt = null,
        )
    }

    private fun isFactoryCockpitExecution(execution: ControllerExecutionInput): Boolean =
        execution.runtimeId == "factory-dashboard" && execution.agentId == "factory-agent"

    private fun respond(result: WorkflowHttpResult): ResponseEntity<WorkflowDataEnvelope<Any?>> =
        ResponseEntity.status(result.status).body(WorkflowDataEnvelope(result.data))

    private fun Boolean?.orFalse(): Boolean = this == true

    private companion object {
        const val CONTROLLER_REQUEST_MAX = 4000
        const val CONTROLLER_REQUEST_SOURCE = "factory-cockpit"
    }
}
