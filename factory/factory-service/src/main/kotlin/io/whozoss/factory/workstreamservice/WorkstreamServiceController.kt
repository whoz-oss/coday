package io.whozoss.factory.workstreamservice

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.agentattempt.domain.toDto
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.config.SessionProperties
import io.whozoss.factory.error.ForbiddenException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.FactoryCaller
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.web.WorkstreamServicePermissions
import io.whozoss.factory.web.factoryError
import io.whozoss.factory.workflow.domain.WorkflowActionTypes
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.service.CreateWorkflowRunService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.web.WorkflowDataEnvelope
import io.whozoss.factory.workstream.WorkstreamService
import io.whozoss.factory.workstream.projection.WorkstreamProjectionService
import io.whozoss.factory.workstream.web.WorkstreamProjectionResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated facade of the conversational `FACTORY_WORKSTREAM` HTTP_API integration.
 *
 * Eight operations replace the former bridge tools: `list_workflows`,
 * `get_workflow`, `get_step_attempts`, `get_blockers`, `get_required_human_actions`,
 * `get_workstream` (read), `start_workflow` and `request_agent_retry` (write).
 *
 * Authorization model (see `factory.security.workstream-service`):
 *  - only the dedicated static `FACTORY_WORKSTREAM` credential may call this facade;
 *  - the tenant (organization/workstream) comes from server configuration, never
 *    from the request;
 *  - every operation checks its own permission (`read`, `start_workflow`,
 *    `request_retry`) derived from that configuration;
 *  - the namespace is resolved exclusively from the configured workstream registry;
 *    workflow and step identifiers supplied by the model are references, never permissions;
 *  - the credential is a service identity: it does not carry the rights of the
 *    person talking to the agent, and it does not prove which case or agent
 *    issued the call.
 *
 * Human validations stay in Factory: this facade lists pending human actions for
 * information only and can never answer them.
 */
@RestController
@RequestMapping("/api/factory/workstream-service")
@Tag(name = "workstream-service", description = "FACTORY_WORKSTREAM HTTP_API facade")
class WorkstreamServiceController(
    private val tenantScopeProvider: TenantScopeProvider,
    private val workflowService: WorkflowService,
    private val durableAttempts: DurableAgentAttemptService,
    private val projectionService: WorkstreamProjectionService,
    private val workstreamService: WorkstreamService,
    private val createWorkflowRunService: CreateWorkflowRunService,
    private val sessionProperties: SessionProperties,
) {

    private data class ServiceCaller(val scope: TenantScope, val identityId: String)

    private val retryLocks = ConcurrentHashMap<String, Any>()

    // ----- reads ---------------------------------------------------------

    @GetMapping("/workflows", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(operationId = "list_workflows", summary = "List the workflows of the configured workstream.")
    fun listWorkflows(
        @RequestParam(name = "state", required = false) state: String?,
        @RequestParam(name = "workflowType", required = false) workflowType: String?,
        @RequestParam(name = "limit", required = false) limit: Int?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = authorize(trustContext, WorkstreamServicePermissions.READ_SCOPE)
        val namespace = workstreamService.requireConfiguredNamespace(caller.scope)
        val resolvedState = state?.takeIf { it.isNotBlank() } ?: "active"
        if (resolvedState !in setOf("active", "removed")) {
            throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "state must be active or removed.")
        }
        val type = workflowType?.takeIf { it.isNotBlank() }?.also {
            if (!ID_PATTERN.matches(it)) throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "workflowType is invalid.")
        }
        val listed = workflowService.listProjections(
            caller.scope,
            namespace,
            resolvedState,
            type,
            (limit ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT),
        )
        val items = (listed["items"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { item ->
            val projection = item["projection"] as? Map<*, *>
            mapOf(
                "workflowId" to item["workflowId"],
                "namespaceId" to item["namespaceId"],
                "workflowType" to projection?.get("workflowType"),
                "title" to projection?.get("title"),
                "status" to projection?.get("status"),
                "revision" to item["revision"],
            )
        }
        return WorkflowDataEnvelope(mapOf("items" to items, "truncated" to (listed["truncated"] == true)))
    }

    @GetMapping("/workflows/{workflowId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(operationId = "get_workflow", summary = "Read the authoritative state of one workflow.")
    fun getWorkflow(
        @PathVariable workflowId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = authorize(trustContext, WorkstreamServicePermissions.READ_SCOPE)
        val id = requireWorkflowId(workflowId)
        val namespace = workstreamService.requireConfiguredNamespace(caller.scope)
        val state = workflowService.getProjection(caller.scope, namespace, id)
        if (state["state"] != "existing") {
            return WorkflowDataEnvelope(mapOf("state" to state["state"], "workflowId" to id, "namespaceId" to namespace))
        }
        val projection = state["projection"] as? Map<*, *>
        val steps = (projection?.get("steps") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { step ->
            mapOf("id" to step["id"], "name" to step["name"], "status" to step["status"])
        }
        val actions = workflowService.workflowActions(caller.scope, namespace, id, canReply = false)
        val canRetry = WorkstreamServicePermissions.RETRY_SCOPE in grantedScopes(trustContext)
        val attempts = durableAttempts.findByWorkflow(caller.scope, namespace, id)
        return WorkflowDataEnvelope(
            linkedMapOf(
                "state" to "existing",
                "workflowId" to id,
                "namespaceId" to namespace,
                "revision" to state["revision"],
                "workflowType" to projection?.get("workflowType"),
                "title" to projection?.get("title"),
                "status" to projection?.get("status"),
                "steps" to steps,
                "blockers" to actions.blockers.take(MAX_ITEMS).map(::blockerView),
                // Only the actions this credential may actually execute are advertised.
                "allowedActions" to actions.allowedActions
                    .filter { canRetry && it.type == WorkflowActionTypes.RETRY }
                    .take(MAX_ITEMS)
                    .map { mapOf("type" to it.type, "stepId" to it.stepId, "expectedRevision" to it.expectedRevision) },
                "attempts" to attempts.take(MAX_ITEMS).map { it.toDto() },
                "attemptsTruncated" to (attempts.size > MAX_ITEMS),
            ),
        )
    }

    @GetMapping("/workflows/{workflowId}/attempts", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(operationId = "get_step_attempts", summary = "List the durable attempts of a workflow, optionally of one step.")
    fun getStepAttempts(
        @PathVariable workflowId: String,
        @RequestParam(name = "stepId", required = false) stepId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = authorize(trustContext, WorkstreamServicePermissions.READ_SCOPE)
        val id = requireWorkflowId(workflowId)
        val namespace = workstreamService.requireConfiguredNamespace(caller.scope)
        val step = stepId?.takeIf { it.isNotBlank() }?.also { requireStepId(it) }
        val attempts = durableAttempts.findByWorkflow(caller.scope, namespace, id)
            .filter { step == null || it.stepId == step }
        return WorkflowDataEnvelope(
            mapOf("items" to attempts.take(MAX_ITEMS).map { it.toDto() }, "truncated" to (attempts.size > MAX_ITEMS)),
        )
    }

    @GetMapping("/workflows/{workflowId}/blockers", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(operationId = "get_blockers", summary = "List the active blockers of a workflow.")
    fun getBlockers(
        @PathVariable workflowId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = authorize(trustContext, WorkstreamServicePermissions.READ_SCOPE)
        val blockers = workflowService
            .workflowActions(caller.scope, workstreamService.requireConfiguredNamespace(caller.scope), requireWorkflowId(workflowId), canReply = false)
            .blockers
        return WorkflowDataEnvelope(
            mapOf("items" to blockers.take(MAX_ITEMS).map(::blockerView), "truncated" to (blockers.size > MAX_ITEMS)),
        )
    }

    @GetMapping("/workflows/{workflowId}/human-actions", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        operationId = "get_required_human_actions",
        summary = "List the pending human decisions of a workflow. Information only: they are answered in Factory.",
    )
    fun getRequiredHumanActions(
        @PathVariable workflowId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = authorize(trustContext, WorkstreamServicePermissions.READ_SCOPE)
        val interactions = openInteractions(
            caller.scope,
            workstreamService.requireConfiguredNamespace(caller.scope),
            requireWorkflowId(workflowId),
        )
        return WorkflowDataEnvelope(
            mapOf(
                "items" to interactions.take(MAX_ITEMS).map { interaction ->
                    mapOf(
                        "interactionId" to interaction["interactionId"],
                        "stepId" to interaction["stepId"],
                        "interactionType" to interaction["interactionType"],
                        "prompt" to interaction["prompt"],
                        "expectedRevision" to interaction["revision"],
                        // Human validations belong to Factory only.
                        "answerableFromThisChannel" to false,
                    )
                },
                "truncated" to (interactions.size > MAX_ITEMS),
            ),
        )
    }

    @GetMapping("/workstreams/{workstreamId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(operationId = "get_workstream", summary = "Read the bounded aggregated projection of the configured workstream.")
    fun getWorkstream(
        @PathVariable workstreamId: String,
        @RequestParam(name = "limit", required = false) limit: Int?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkstreamProjectionResponse> {
        val caller = authorize(trustContext, WorkstreamServicePermissions.READ_SCOPE)
        val namespace = workstreamService.requireConfiguredNamespace(caller.scope)
        // The boundary check rejects any workstream other than the configured one.
        val factoryCaller = FactoryCaller(caller.scope, caller.identityId, caller.identityId, null)
        return ResponseEntity.ok(projectionService.getAggregatedProjection(factoryCaller, workstreamId, namespace, limit))
    }

    // ----- writes --------------------------------------------------------

    @PostMapping("/workflows", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(operationId = "start_workflow", summary = "Create a workflow run. Factory owns its identity and execution.")
    fun startWorkflow(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
        val caller = authorize(trustContext, WorkstreamServicePermissions.START_SCOPE)
        val request = body ?: throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST)
        if (request.keys.any { it !in START_FIELDS }) throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST)
        val namespace = workstreamService.requireConfiguredNamespace(caller.scope)
        val workflowType = (request["workflowType"] as? String)?.trim()
            ?.takeIf { ID_PATTERN.matches(it) }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "workflowType is invalid.")
        val title = (request["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null && title.length > MAX_TITLE) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "title must not exceed $MAX_TITLE characters.")
        }
        val ticket = (request["ticket"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (ticket != null && !TICKET_PATTERN.matches(ticket)) {
            throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "ticket is invalid.")
        }
        val key = idempotencyKey?.trim()?.takeIf { KEY_PATTERN.matches(it) }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_START_REQUEST, "A valid Idempotency-Key is required.")
        assertWorkflowTypeAllowed(caller.scope, workflowType)

        // The key is namespaced by the verified service identity, so two callers
        // can never collide, and an identical key with a different payload is
        // rejected by the existing command-hash identity check.
        val result = createWorkflowRunService.create(
            scope = caller.scope,
            namespaceId = namespace,
            actorId = caller.identityId,
            // No runtime case is invented: this run is not attributed to an AgentOS case.
            caseId = null,
            command = CreateWorkflowRunService.Command(
                workflowType = workflowType,
                title = title,
                initialRequest = null,
                parameters = ticket?.let { mapOf("ticket" to it) } ?: emptyMap(),
                idempotencyKey = "svc:${caller.identityId}:$key",
            ),
            repoRoot = sessionProperties.defaultRepoRoot?.takeIf { it.isNotBlank() },
        )
        val data = result.data as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val view = linkedMapOf<String, Any?>(
            "workflowId" to data["workflowId"],
            "namespaceId" to namespace,
            "title" to data["title"],
            "revision" to data["revision"],
            "created" to data["created"],
            "queued" to data["queued"],
            "idempotent" to data["idempotent"],
            "submissionId" to data["submissionId"],
            "submissionStatus" to data["submissionStatus"],
        )
        return ResponseEntity.status(result.status).body(WorkflowDataEnvelope(view))
    }

    @PostMapping("/workflows/{workflowId}/retries", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(operationId = "request_agent_retry", summary = "Request a governed retry of a blocked step.")
    fun requestRetry(
        @PathVariable workflowId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
        val caller = authorize(trustContext, WorkstreamServicePermissions.RETRY_SCOPE)
        val id = requireWorkflowId(workflowId)
        val request = body ?: throw workflowException("INVALID_RETRY_REQUEST", "Retry request is invalid.")
        if (request.keys.any { it !in RETRY_FIELDS }) throw workflowException("INVALID_RETRY_REQUEST", "Retry request is invalid.")
        val namespace = workstreamService.requireConfiguredNamespace(caller.scope)
        val stepId = (request["stepId"] as? String)?.also { requireStepId(it) }
            ?: throw workflowException("INVALID_RETRY_REQUEST", "stepId is required.")
        val expectedRevision = (request["expectedRevision"] as? Number)?.toInt()
            ?: throw workflowException("INVALID_RETRY_REQUEST", "expectedRevision is required.")
        val reasonCode = (request["reasonCode"] as? String)?.takeIf { REASON_PATTERN.matches(it) }
            ?: throw workflowException("INVALID_RETRY_REQUEST", "reasonCode is invalid.")

        // Factory-owned idempotence: at most one open retry request per step. A
        // repeated call returns the existing request instead of opening another.
        val lock = retryLocks.computeIfAbsent("${caller.scope.organizationId}|${caller.scope.workstreamId}|$namespace|$id|$stepId") { Any() }
        return synchronized(lock) {
            val existing = openInteractions(caller.scope, namespace, id).firstOrNull {
                it["interactionType"] == "retry" && it["stepId"] == stepId && it["status"] == "waiting"
            }
            if (existing != null) {
                ResponseEntity.ok(WorkflowDataEnvelope(retryView(id, stepId, existing, idempotent = true)))
            } else {
                val opened = workflowService.openRetry(caller.scope, namespace, id, stepId, expectedRevision, reasonCode)
                val interaction = (opened.data as? Map<*, *>)?.get("interaction") as? Map<*, *>
                    ?: throw workflowException(WorkflowErrorCodes.HUMAN_INTERACTION_FAILURE, "The retry request was not recorded.")
                ResponseEntity.status(opened.status).body(WorkflowDataEnvelope(retryView(id, stepId, interaction, idempotent = false)))
            }
        }
    }

    // ----- helpers -------------------------------------------------------

    private fun authorize(trustContext: TrustContext?, requiredScope: String): ServiceCaller {
        val context = trustContext
            ?: throw workflowException(WorkflowErrorCodes.TRUST_CONTEXT_UNAVAILABLE, "Trust context unavailable")
        val scope = tenantScopeProvider.scopeOf(context)
            ?: throw workflowException(WorkflowErrorCodes.TRUST_CONTEXT_UNAVAILABLE, "Trust context unavailable")
        if (context.authenticationMethod != TrustContext.AUTH_STATIC_WORKSTREAM_BEARER) {
            throw ForbiddenException("This route requires the FACTORY_WORKSTREAM service credential")
        }
        if (requiredScope !in context.scopes) {
            throw ForbiddenException("The service credential does not hold the required permission")
        }
        val identity = context.serviceIdentityId?.takeIf { it.isNotBlank() }
            ?: throw ForbiddenException("The verified service identity is unavailable")
        return ServiceCaller(scope, identity)
    }

    private fun grantedScopes(trustContext: TrustContext?): List<String> = trustContext?.scopes.orEmpty()

    private fun assertWorkflowTypeAllowed(scope: TenantScope, workflowType: String) {
        val workstream = workstreamService.findDomain(scope, scope.workstreamId)
            ?: factoryError(403, "WORKSTREAM_NAMESPACE_MAPPING_INVALID", "The workstream namespace mapping is unavailable.")
        if (workstream.allowedWorkflowTypes.isNotEmpty() && workflowType !in workstream.allowedWorkflowTypes) {
            factoryError(403, "WORKSTREAM_BOUNDARY_VIOLATION", "The workflow type is not allowed in this workstream.")
        }
    }

    private fun openInteractions(scope: TenantScope, namespace: String, workflowId: String): List<Map<*, *>> =
        ((workflowService.listInteractions(scope, namespace, workflowId, openOnly = true).data as? Map<*, *>)?.get("items") as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()

    private fun retryView(workflowId: String, stepId: String, interaction: Map<*, *>, idempotent: Boolean): Map<String, Any?> =
        linkedMapOf(
            "status" to "pending-human",
            "workflowId" to workflowId,
            "stepId" to stepId,
            "interactionId" to interaction["interactionId"],
            "revision" to interaction["revision"],
            "idempotent" to idempotent,
            "message" to "Retry requested for step $stepId; awaiting human approval in Factory.",
        )

    private fun blockerView(blocker: io.whozoss.factory.workflow.domain.WorkflowBlockerDto): Map<String, Any?> =
        mapOf("code" to blocker.code, "stepId" to blocker.stepId, "message" to blocker.message)

    private fun requireWorkflowId(raw: String): String =
        raw.takeIf { ID_PATTERN.matches(it) }
            ?: throw workflowException(WorkflowErrorCodes.INVALID_WORKFLOW_ID, "workflowId is invalid.")

    private fun requireStepId(raw: String) {
        if (!STEP_PATTERN.matches(raw)) throw workflowException(WorkflowErrorCodes.INVALID_REQUEST, "stepId is invalid.")
    }

    private companion object {
        const val MAX_ITEMS = 50
        const val DEFAULT_LIST_LIMIT = 50
        const val MAX_LIST_LIMIT = 200
        const val MAX_TITLE = 200

        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val STEP_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$")
        val KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$")
        val TICKET_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
        val REASON_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")
        val START_FIELDS = setOf("workflowType", "title", "ticket")
        val RETRY_FIELDS = setOf("stepId", "expectedRevision", "reasonCode")
    }
}
