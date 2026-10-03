package io.whozoss.factory.workflow.service

import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.CaseEventView
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.proxy.AgentOsUnavailableException
import io.whozoss.factory.proxy.UsageTrackingUnavailableException
import io.whozoss.factory.workflow.domain.AllowedActionDto
import io.whozoss.factory.workflow.domain.CanonicalHash
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.TransitionDecision
import io.whozoss.factory.workflow.domain.TransitionRequestValidation
import io.whozoss.factory.workflow.domain.WorkflowActionTypes
import io.whozoss.factory.workflow.domain.WorkflowActionsResponseDto
import io.whozoss.factory.workflow.domain.WorkflowBlockerCodes
import io.whozoss.factory.workflow.domain.WorkflowBlockerDto
import io.whozoss.factory.workflow.domain.WorkflowCodeTransitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowExecution
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowPolicyDefinition
import io.whozoss.factory.workflow.domain.WorkflowPolicyEvidence
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionValidator
import io.whozoss.factory.workflow.domain.WorkflowSnapshot
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.domain.WorkflowTransitionPolicy
import io.whozoss.factory.workflow.domain.WorkflowTransitionRequest
import io.whozoss.factory.workflow.domain.createWorkflowInstance
import io.whozoss.factory.workflow.domain.nowIso
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.persistence.EvidenceAppendResult
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.ProjectionPublishResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.sse.WorkflowProjectionEvents
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.util.UUID

/** An HTTP-shaped service result: status + payload. */
data class WorkflowHttpResult(val status: Int, val data: Any?)

/**
 * Application service of the Workflow aggregate (A6).
 *
 * Orchestrates the ports ([WorkflowRepository], [WorkflowEvidenceRepository],
 * [HumanInteractionRepository]) and the pure domain policies
 * ([WorkflowTransitionPolicy], [WorkflowDefinitionValidator],
 * [WorkflowProjectionValidator]). After every durably successful change it
 * publishes a best-effort SSE invalidation hint through [WorkflowSseHub].
 *
 * The interaction -> evidence -> transition path is a single
 * `@Transactional` method, so a failure at any step rolls the whole reply back.
 */
@Service
class WorkflowService(
    private val repository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val interactionRepository: HumanInteractionRepository,
    private val sseHub: WorkflowSseHub,
    /**
     * Optional DAG sequencer. When present, closing a `checkpoint` interaction
     * automatically resumes the session instead of requiring a manual
     * `POST /continue`.
     */
    private val sessionRunService: SessionRunService? = null,
    /**
     * Optional AgentOS proxy used to resolve the namespace repo root for the
     * automatic resumption (mirrors
     * [io.whozoss.factory.agentattempt.service.OutboxDrainWorker]).
     */
    private val agentOsProxyClient: AgentOsProxyClient? = null,
    /**
     * Optional transaction manager used to bracket the atomic reply writes in one
     * short, isolated transaction that commits BEFORE the session resumption
     * starts. Injected in production; pure unit tests may omit it.
     */
    private val transactionManager: PlatformTransactionManager? = null,
    private val durableAgentAttemptService: DurableAgentAttemptService? = null,
    private val agentOsExecutionAdapter: AgentOsExecutionAdapter? = null,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Short, isolated transaction boundary for the human reply. `REQUIRES_NEW`
     * guarantees the reply commits durably before [resumeCheckpointSession] runs, so
     * a failing resumption can never roll the human decision back.
     */
    private val replyTransaction: TransactionTemplate? = transactionManager?.let { manager ->
        TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    }

    /** Runs [block] in a fresh short transaction, or inline when none is configured. */
    private fun <T : Any> inReplyTransaction(block: () -> T): T {
        val template = replyTransaction ?: return block()
        return template.execute { block() }!!
    }

    /** Durable outcome of the transactional part of a human reply. */
    private data class HumanReplyOutcome(val interaction: HumanInteractionRecord, val result: WorkflowHttpResult)

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    @Transactional
    fun registerDefinition(scope: TenantScope, record: WorkflowDefinitionRecord) {
        repository.saveDefinition(scope, record)
    }

    /** Deletes a definition; `false` when no definition matches the given identity. */
    @Transactional
    fun deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean =
        repository.deleteDefinition(scope, workflowType, version)

    @Transactional(readOnly = true)
    fun listDefinitions(scope: TenantScope): Map<String, Any?> =
        mapOf("items" to repository.listDefinitions(scope).map { definitionJson(it) })

    @Transactional(readOnly = true)
    fun getDefinition(scope: TenantScope, workflowType: String, version: String): Map<String, Any?>? =
        repository.findDefinition(scope, workflowType, version)?.let { definitionJson(it) }

    private fun definitionJson(record: WorkflowDefinitionRecord): Map<String, Any?> = mapOf(
        "workflowType" to record.workflowType,
        "version" to record.version,
        "definitionHash" to record.definitionHash,
        "definition" to record.definition,
    )

    // ------------------------------------------------------------------
    // Projections (declarative store + governed instances)
    // ------------------------------------------------------------------

    /**
     * Lists workflow projections of the caller's tenant scope.
     *
     * [namespaceId] is an OPTIONAL filter: when it is `null`/blank every
     * namespace of the scope is listed; when it is supplied only that namespace
     * is returned. The tenant isolation always comes from [scope] — never from
     * the namespace filter.
     */
    @Transactional(readOnly = true)
    fun listProjections(scope: TenantScope, namespaceId: String?, state: String): Map<String, Any?> {
        if (state !in setOf("active", "removed")) {
            throw workflowException(WorkflowErrorCodes.UNSUPPORTED_STATE, "state must be active or removed.")
        }
        val resolvedNamespace = namespaceId?.takeIf { it.isNotBlank() }
        val items = repository.listProjections(scope, resolvedNamespace, state).map { publicSnapshot(it) }
        return mapOf("namespaceId" to (resolvedNamespace ?: ""), "state" to state, "items" to items)
    }

    @Transactional(readOnly = true)
    fun getProjection(scope: TenantScope, namespaceId: String, workflowId: String): Map<String, Any?> {
        val projection = repository.findProjection(scope, namespaceId, workflowId)
        if (projection != null) {
            return when (projection.lifecycleState) {
                "active" -> mapOf("namespaceId" to namespaceId, "state" to "existing") + publicSnapshot(projection)
                "removed" -> mapOf("namespaceId" to namespaceId, "workflowId" to workflowId, "state" to "removed")
                else -> mapOf("namespaceId" to namespaceId, "workflowId" to workflowId, "state" to "purged")
            }
        }
        val instance = repository.findInstance(scope, namespaceId, workflowId)
        return if (instance != null && instance.status == "active") {
            mapOf("namespaceId" to namespaceId, "state" to "existing") + publicInstanceSnapshot(instance)
        } else {
            mapOf("namespaceId" to namespaceId, "workflowId" to workflowId, "state" to "absent")
        }
    }

    private fun publicSnapshot(record: WorkflowProjectionRecord): Map<String, Any?> = buildMap {
        put("workflowId", record.workflowId)
        // Namespace attribution of the row: required by the cockpit to route a
        // scope-wide list item to its namespace-scoped detail/timeline view.
        put("namespaceId", record.namespaceId)
        put("revision", record.revision)
        put("projectionHash", record.projectionHash)
        if (record.governanceMode != null) {
            put("governanceMode", record.governanceMode)
            put("definitionVersion", record.definitionVersion)
            put("definitionHash", record.definitionHash)
            put("relations", record.relations ?: mapOf("rootWorkflowId" to record.workflowId))
            put("instance", record.instance)
        } else {
            put("relations", mapOf("rootWorkflowId" to record.workflowId))
        }
        val controllerRequest = (record.instance?.get("controllerRequest") as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
            ?: (record.projection["controllerRequest"] as? Map<*, *>)
                ?.entries
                ?.associate { it.key.toString() to it.value }
        if (controllerRequest != null) put("controllerRequest", controllerRequest)
        val activeController = (record.instance?.get("controllerExecution") as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
        if (activeController != null) put("controllerExecution", activeController)
        else if (record.controllerExecution != null) put("controllerExecution", record.controllerExecution)
        put("projection", record.projection)
    }

    private fun publicInstanceSnapshot(record: WorkflowInstanceRecord): Map<String, Any?> = buildMap {
        put("workflowId", record.workflowId)
        put("namespaceId", record.namespaceId)
        put("revision", record.revision)
        put("projectionHash", CanonicalHash.workflowProjectionHash(record.projection))
        put("governanceMode", record.instance["governanceMode"])
        put("definitionVersion", record.instance["definitionVersion"])
        put("definitionHash", record.instance["definitionHash"])
        put("relations", record.instance["relations"] ?: mapOf("rootWorkflowId" to record.workflowId))
        put("instance", record.instance)
        val controllerRequest = (record.instance["controllerRequest"] as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
        if (controllerRequest != null) put("controllerRequest", controllerRequest)
        val activeController = (record.instance["controllerExecution"] as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
        if (activeController != null) put("controllerExecution", activeController)
        put("projection", record.projection)
    }

    /**
     * Declarative publication (`PUT /workflows/{id}/projection`). `expectedRevision`
     * is the optional optimistic-locking command precondition: `0` creates,
     * `N >= 1` compare-and-swaps, `null` is unconditional.
     */
    @Transactional
    fun publishProjection(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        projection: Any?,
        expectedRevision: Int?,
        controllerExecution: Map<String, Any?>?,
    ): WorkflowHttpResult {
        val validation = WorkflowProjectionValidator.validate(projection, workflowId)
        if (validation is io.whozoss.factory.workflow.domain.ProjectionValidation.Invalid) {
            throw workflowException(validation.error.code, "Workflow projection is invalid.", validation.error.details)
        }
        val normalized = (validation as io.whozoss.factory.workflow.domain.ProjectionValidation.Valid).normalized
        val hash = CanonicalHash.workflowProjectionHash(normalized)
        val record = WorkflowProjectionRecord(
            namespaceId = namespaceId,
            workflowId = workflowId,
            schemaVersion = normalized["schemaVersion"] as String,
            revision = 0,
            projectionHash = hash,
            status = normalized["status"] as String,
            projection = normalized,
            instance = null,
            governanceMode = null,
            definitionVersion = null,
            definitionHash = null,
            relations = null,
            controllerExecution = controllerExecution,
            lifecycleState = "active",
        )
        return when (val result = repository.publishProjection(scope, record, expectedRevision)) {
            is ProjectionPublishResult.Changed -> {
                sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to result.record.revision))
                WorkflowHttpResult(
                    201.takeIf { result.record.revision == 1 } ?: 200,
                    mapOf("namespaceId" to namespaceId, "changed" to true) + publicSnapshot(result.record),
                )
            }
            is ProjectionPublishResult.Idempotent -> WorkflowHttpResult(
                200,
                mapOf("namespaceId" to namespaceId, "changed" to false) + publicSnapshot(result.record),
            )
            is ProjectionPublishResult.Conflict -> throw workflowException(
                result.code,
                if (result.code == WorkflowErrorCodes.REVISION_CONFLICT) "The expected revision is stale." else result.code,
            )
        }
    }

    // ------------------------------------------------------------------
    // Start
    // ------------------------------------------------------------------

    @Transactional
    fun start(
        scope: TenantScope,
        namespaceId: String,
        command: WorkflowStartCommand,
        controllerExecution: ControllerExecutionInput,
    ): WorkflowHttpResult {
        val definitionRecord = resolveUniqueDefinition(scope, command.workflowType)
        val definitionInput = WorkflowDefinitionInput(
            workflowType = definitionRecord.workflowType,
            version = definitionRecord.version,
            definitionHash = definitionRecord.definitionHash,
            steps = definitionRecord.toPolicyDefinition().steps,
        )
        val existing = repository.findInstance(scope, namespaceId, command.workflowId)
        if (existing != null) {
            if (existing.status != "active") throw workflowException(WorkflowErrorCodes.WORKFLOW_REMOVED)
            val hash = CanonicalHash.workflowStartCommandHash(command, definitionInput)
            if (existing.creationCommandHash != hash) {
                throw workflowException(WorkflowErrorCodes.WORKFLOW_IDENTITY_CONFLICT)
            }
            return WorkflowHttpResult(
                200,
                mapOf("namespaceId" to namespaceId, "created" to false, "idempotent" to true) + publicInstanceSnapshot(existing),
            )
        }
        val created = createWorkflowInstance(command, definitionInput, controllerExecution)
        val record = WorkflowInstanceRecord(
            namespaceId = namespaceId,
            workflowId = command.workflowId,
            revision = 1,
            status = "active",
            creationCommandHash = created.creationCommandHash,
            instance = created.instance,
            projection = created.projection,
        )
        repository.insertInstance(scope, record)
        publishProjectionRow(scope, namespaceId, command.workflowId, created.projection, command.workflowType, definitionRecord, controllerExecution)
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to command.workflowId, "namespaceId" to namespaceId, "revision" to 1))
        return WorkflowHttpResult(
            201,
            mapOf("namespaceId" to namespaceId, "created" to true, "idempotent" to false) + publicInstanceSnapshot(record),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun publishProjectionRow(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        projection: Map<String, Any?>,
        workflowType: String,
        definitionRecord: WorkflowDefinitionRecord,
        controllerExecution: ControllerExecutionInput,
    ) {
        val instanceRecord = repository.findInstance(scope, namespaceId, workflowId)
        val record = WorkflowProjectionRecord(
            namespaceId = namespaceId,
            workflowId = workflowId,
            schemaVersion = (projection["schemaVersion"] as? String) ?: "2",
            revision = 0,
            projectionHash = CanonicalHash.workflowProjectionHash(projection),
            status = (projection["status"] as? String) ?: "ready",
            projection = projection,
            instance = instanceRecord?.instance,
            governanceMode = "governed",
            definitionVersion = definitionRecord.version,
            definitionHash = definitionRecord.definitionHash,
            relations = (instanceRecord?.instance?.get("relations") as? Map<String, Any?>) ?: mapOf("rootWorkflowId" to workflowId),
            controllerExecution = controllerExecution.toJson(),
            lifecycleState = "active",
        )
        try {
            repository.publishProjection(scope, record, 0)
        } catch (_: io.whozoss.factory.workflow.domain.WorkflowException) {
            // A projection already exists for this workflow (e.g. replayed start); keep it.
        }
    }

    private fun resolveUniqueDefinition(scope: TenantScope, workflowType: String): WorkflowDefinitionRecord {
        val matches = repository.listDefinitions(scope).filter { it.workflowType == workflowType }
        if (matches.isEmpty()) throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND)
        if (matches.size > 1) throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_AMBIGUOUS)
        return matches.first()
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    @Transactional
    fun transition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        requestInput: Any?,
        executionInput: WorkflowExecution,
    ): WorkflowHttpResult {
        val validation = WorkflowTransitionPolicy.validateRequest(requestInput, workflowId)
        if (validation is TransitionRequestValidation.Invalid) {
            throw workflowException(validation.code, "Transition request is invalid.")
        }
        val request = (validation as TransitionRequestValidation.Valid).request
        val instance = activeInstance(scope, namespaceId, workflowId)
        val snapshot = instance.toSnapshot()
        val definition = resolveDefinition(scope, instance)
        val evidence = evidenceRepository.list(scope, namespaceId, workflowId).map { it.toPolicyEvidence() }
        val execution = executionInput.copy(namespaceId = namespaceId)
        val decision = WorkflowTransitionPolicy.evaluateTransition(request, snapshot, definition, evidence, execution)
        if (decision is TransitionDecision.Denied) throw decision.toException()
        val applied = WorkflowTransitionPolicy.applyTransition(snapshot, definition, request)
        if (!repository.updateInstance(scope, namespaceId, workflowId, request.expectedRevision, applied.toInstance(instance))) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The expected revision is stale.")
        }
        repository.appendTransition(
            scope,
            namespaceId,
            workflowId,
            transitionId = request.requestId,
            request = request,
            fromStepId = snapshot.stepStatus(request.stepId)?.let { request.stepId },
            toStepId = request.stepId,
            payload = mapOf("expectedRevision" to request.expectedRevision, "requestedStatus" to request.requestedStatus),
        )
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to applied.revision))
        return WorkflowHttpResult(
            200,
            mapOf(
                "workflowId" to workflowId,
                "requestId" to request.requestId,
                "revision" to applied.revision,
                "changed" to true,
                "idempotent" to false,
                "projection" to applied.projection,
            ),
        )
    }

    @Transactional
    fun codeTransition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        requestInput: Any?,
    ): WorkflowHttpResult {
        val validation = WorkflowTransitionPolicy.validateRequest(requestInput, workflowId)
        if (validation is TransitionRequestValidation.Invalid) {
            throw workflowException(validation.code, "Transition request is invalid.")
        }
        val request = (validation as TransitionRequestValidation.Valid).request
        val instance = activeInstance(scope, namespaceId, workflowId)
        val snapshot = instance.toSnapshot()
        val definition = resolveDefinition(scope, instance)
        val evidence = evidenceRepository.list(scope, namespaceId, workflowId).map { it.toPolicyEvidence() }
        val execution = WorkflowExecution(
            kind = "factory-oracle",
            runtimeId = "factory-dashboard",
            agentId = "factory-oracle",
            namespaceId = namespaceId,
        )
        val decision = WorkflowTransitionPolicy.evaluateTransition(request, snapshot, definition, evidence, execution)
        if (decision is TransitionDecision.Denied) throw decision.toException()
        val applied = WorkflowTransitionPolicy.applyTransition(snapshot, definition, request)
        if (!repository.updateInstance(scope, namespaceId, workflowId, request.expectedRevision, applied.toInstance(instance))) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The expected revision is stale.")
        }
        repository.appendCodeTransition(
            scope,
            namespaceId,
            workflowId,
            WorkflowCodeTransitionRecord(
                codeTransitionId = request.requestId,
                stepId = request.stepId,
                outcome = request.requestedStatus,
                exitCode = null,
                payload = mapOf("requestedStatus" to request.requestedStatus),
                createdAt = null,
            ),
        )
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to applied.revision))
        return WorkflowHttpResult(
            200,
            mapOf(
                "workflowId" to workflowId,
                "revision" to applied.revision,
                "changed" to true,
                "idempotent" to false,
                "projection" to applied.projection,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Evidence
    // ------------------------------------------------------------------

    @Transactional
    fun listEvidence(scope: TenantScope, namespaceId: String, workflowId: String, stepId: String?): WorkflowHttpResult {
        activeInstance(scope, namespaceId, workflowId)
        val items = evidenceRepository.list(scope, namespaceId, workflowId, stepId).map { it.toJson() }
        return WorkflowHttpResult(200, mapOf("namespaceId" to namespaceId, "workflowId" to workflowId, "items" to items))
    }

    @Transactional
    fun appendEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        item: WorkflowEvidenceItem,
    ): WorkflowHttpResult {
        activeInstance(scope, namespaceId, workflowId)
        return when (val result = evidenceRepository.append(scope, namespaceId, workflowId, item)) {
            is EvidenceAppendResult.Created -> WorkflowHttpResult(
                201,
                mapOf(
                    "namespaceId" to namespaceId,
                    "workflowId" to workflowId,
                    "created" to true,
                    "idempotent" to false,
                    "evidence" to result.item.toJson(),
                ),
            )
            is EvidenceAppendResult.Idempotent -> WorkflowHttpResult(
                200,
                mapOf(
                    "namespaceId" to namespaceId,
                    "workflowId" to workflowId,
                    "created" to false,
                    "idempotent" to true,
                    "evidence" to result.item.toJson(),
                ),
            )
            is EvidenceAppendResult.Collision -> throw workflowException(
                result.code,
                "The idempotency key was already used for different evidence.",
            )
        }
    }

    // ------------------------------------------------------------------
    // AgentOS question answers
    // ------------------------------------------------------------------

    fun submitAgentQuestionAnswer(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        questionEventId: String,
        answer: String,
        actorId: String,
    ): WorkflowHttpResult {
        val attempts = durableAgentAttemptService
            ?: throw workflowException("AGENT_ANSWER_UNAVAILABLE", "The AgentOS execution bridge is unavailable.")
        val adapter = agentOsExecutionAdapter
            ?: throw workflowException("AGENT_ANSWER_UNAVAILABLE", "The AgentOS execution bridge is unavailable.")
        val attemptId = io.whozoss.factory.capability.CapabilityExecutionService.stableAttemptId(workflowId, stepId)
        val attempt = attempts.find(scope, namespaceId, workflowId, stepId, attemptId)
            ?: throw workflowException("AGENT_ATTEMPT_NOT_FOUND", "No AgentOS attempt belongs to this workflow step.")
        if (attempt.status != AgentAttemptStatus.WAITING_HUMAN) {
            throw workflowException("AGENT_QUESTION_STALE", "The AgentOS attempt is not awaiting a human answer.")
        }
        val events = adapter.persistedEvents(attempt.caseId)
        val question = events.lastOrNull { it.type == CaseEventView.QUESTION_EVENT && it.eventId == questionEventId }
            ?: throw workflowException("AGENT_QUESTION_NOT_FOUND", "The projected question is not present in persisted AgentOS history.")
        if (question.caseId != attempt.caseId) {
            throw workflowException("AGENT_QUESTION_MISMATCH", "The question does not belong to the authoritative AgentOS case.")
        }
        if (events.any { it.type == CaseEventView.ANSWER_EVENT && it.answeredQuestionId == questionEventId }) {
            throw workflowException("AGENT_QUESTION_ALREADY_ANSWERED", "The AgentOS question was already answered.")
        }
        val activeQuestion = events.lastOrNull { event ->
            event.type == CaseEventView.QUESTION_EVENT &&
                events.none { it.type == CaseEventView.ANSWER_EVENT && it.answeredQuestionId == event.eventId }
        }
        if (activeQuestion?.eventId != questionEventId) {
            throw workflowException("AGENT_QUESTION_STALE", "The AgentOS question is no longer active.")
        }
        // question.userId is an AgentOS-internal UUID while actorId is the
        // authenticated external identity resolved at the Factory boundary.
        // Comparing those two identity domains as strings rejects legitimate
        // users. Forward the trusted external identity and let AgentOS resolve
        // it to its internal user and enforce the QuestionEvent recipient.
        if (actorId.isBlank()) {
            throw workflowException("AGENT_ANSWER_IDENTITY_REQUIRED", "An authenticated user is required to answer this question.")
        }
        val normalized = answer.trim()
        if (normalized.isEmpty() || normalized.length > MAX_AGENT_ANSWER_LENGTH) {
            throw workflowException("INVALID_AGENT_ANSWER", "answer must contain 1 to $MAX_AGENT_ANSWER_LENGTH characters.")
        }
        when (question.questionType ?: "FREE_TEXT") {
            "FREE_TEXT", "OPEN_CHOICE" -> Unit
            "SINGLE_CHOICE" -> if (normalized !in question.questionOptions) {
                throw workflowException("INVALID_AGENT_ANSWER", "answer must be one of the persisted question options.")
            }
            "OAUTH_AUTHORIZE" -> throw workflowException(
                "UNSUPPORTED_AGENT_QUESTION_TYPE",
                "OAuth authorization questions cannot be answered from Factory.",
            )
            else -> throw workflowException("UNSUPPORTED_AGENT_QUESTION_TYPE", "Unsupported AgentOS question type.")
        }
        adapter.answerQuestion(attempt.caseId, questionEventId, normalized, attemptId, actorId)
        return WorkflowHttpResult(
            202,
            mapOf(
                "workflowId" to workflowId,
                "stepId" to stepId,
                "attemptId" to attemptId,
                "questionEventId" to questionEventId,
                "status" to "accepted",
            ),
        )
    }

    // ------------------------------------------------------------------
    // Human interactions (atomic reply)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun listInteractions(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        openOnly: Boolean,
    ): WorkflowHttpResult {
        val items = interactionRepository.list(scope, namespaceId, workflowId, openOnly).map { it.toJson() }
        return WorkflowHttpResult(200, mapOf("namespaceId" to namespaceId, "workflowId" to workflowId, "items" to items))
    }

    @Transactional
    fun openInteraction(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        expectedRevision: Int,
        prompt: String,
        actions: List<Map<String, Any?>>,
        idempotencyKey: String,
    ): WorkflowHttpResult {
        val instance = activeInstance(scope, namespaceId, workflowId)
        val snapshot = instance.toSnapshot()
        val definition = resolveDefinition(scope, instance)
        val validation = WorkflowTransitionPolicy.validateRequest(
            mapOf(
                "workflowId" to workflowId,
                "stepId" to stepId,
                "expectedRevision" to expectedRevision,
                "requestedStatus" to "waiting_human",
                "evidenceIds" to emptyList<String>(),
                "idempotencyKey" to "human-open:$idempotencyKey",
            ),
            workflowId,
        )
        if (validation is TransitionRequestValidation.Invalid) throw workflowException(WorkflowErrorCodes.INVALID_INTERACTION)
        val request = (validation as TransitionRequestValidation.Valid).request
        val execution = WorkflowExecution(kind = "factory-human-gate", runtimeId = "factory-dashboard", agentId = "factory-runner")
        val decision = WorkflowTransitionPolicy.evaluateHumanCheckpointOpen(request, snapshot, definition, execution)
        if (decision is TransitionDecision.Denied) throw decision.toException()
        val applied = WorkflowTransitionPolicy.applyHumanCheckpointOpen(snapshot, request)
        if (!repository.updateInstance(scope, namespaceId, workflowId, expectedRevision, applied.toInstance(instance))) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The expected revision is stale.")
        }
        repository.appendTransition(
            scope,
            namespaceId,
            workflowId,
            transitionId = request.requestId,
            request = request,
            fromStepId = stepId,
            toStepId = stepId,
            payload = mapOf("kind" to "human_checkpoint_open"),
        )
        val interactionId = UUID.randomUUID().toString()
        val interaction = interactionRepository.insert(
            scope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                interactionType = "approval",
                status = "waiting",
                revision = applied.revision,
                payload = linkedMapOf(
                    "stepId" to stepId,
                    "prompt" to prompt,
                    "actions" to actions,
                    "idempotencyKey" to idempotencyKey,
                ),
            ),
        )
        interactionRepository.appendEvent(
            scope,
            namespaceId,
            workflowId,
            HumanInteractionEventRecord(
                eventId = UUID.randomUUID().toString(),
                interactionId = interactionId,
                eventType = "interaction_opened",
                actorId = "factory-human-gate",
                payload = mapOf("revision" to applied.revision),
            ),
        )
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to applied.revision))
        return WorkflowHttpResult(
            201,
            mapOf(
                "workflowId" to workflowId,
                "interaction" to interaction.toJson(),
                "revision" to applied.revision,
                "idempotent" to false,
                "projection" to applied.projection,
            ),
        )
    }

    /**
     * Human reply: the interaction update, the audited `human-decision` evidence,
     * the governed transition and the interaction closure all commit in ONE short,
     * isolated transaction. Only once that transaction has committed is the
     * session resumption triggered — outside the reply transaction — so a failing
     * (or long) resumption can never roll the human reply back.
     */
    fun replyInteraction(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
        expectedRevision: Int,
        actionId: String,
        text: String?,
        actorId: String,
        repoRoot: Path? = null,
    ): WorkflowHttpResult {
        // Transaction 1 (short): commit the human response + evidence + transition
        // + interaction closure atomically.
        val outcome = inReplyTransaction {
            replyInteractionTransactional(scope, namespaceId, workflowId, interactionId, expectedRevision, actionId, text, actorId)
        }
        // Post-transaction resumption: a separate execution context that does not
        // join (or inherit) the committed reply transaction.
        resumeCheckpointSession(scope, namespaceId, workflowId, outcome.interaction, repoRoot)
        return outcome.result
    }

    private fun replyInteractionTransactional(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
        expectedRevision: Int,
        actionId: String,
        text: String?,
        actorId: String,
    ): HumanReplyOutcome {
        val interaction = interactionRepository.find(scope, namespaceId, workflowId, interactionId)
            ?: throw workflowException(WorkflowErrorCodes.INTERACTION_NOT_FOUND)
        val actions = (interaction.payload["actions"] as? List<*>).orEmpty().mapNotNull { entry ->
            (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }
        val action = actions.firstOrNull { it["id"] == actionId }
            ?: throw workflowException(WorkflowErrorCodes.ACTION_NOT_ALLOWED)
        val instance = activeInstance(scope, namespaceId, workflowId)
        val snapshot = instance.toSnapshot()
        val definition = resolveDefinition(scope, instance)
        val isRetry = interaction.interactionType == "retry"
        val expectedStepStatus = if (isRetry) "blocked" else "waiting_human"
        if (snapshot.stepStatus(interaction.stepId) != expectedStepStatus) {
            throw workflowException(WorkflowErrorCodes.INTERACTION_STALE, "The human step is not awaiting an answer.")
        }
        val interactionRevision = interaction.revision
        // The workflow revision is authoritative for the state-machine CAS; the
        // interaction revision is the caller's optimistic lock. The two are equal
        // in the governed frontend flow, but a DAG checkpoint can legitimately be
        // opened at `workflowRevision` and resolved after the sequencer persisted
        // further runs, so they must not be conflated.
        val workflowRevision = snapshot.revision
        if (expectedRevision != interactionRevision) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The expected revision is stale.")
        }
        val outcome = if (actionId == "approve") "pass" else "fail"
        val evidenceItem = WorkflowEvidenceItem(
            evidenceId = UUID.randomUUID().toString(),
            namespaceId = namespaceId,
            workflowId = workflowId,
            stepId = interaction.stepId,
            kind = "human-decision",
            outcome = outcome,
            source = mapOf("kind" to "factory-human", "runtimeId" to "factory-dashboard", "actorId" to actorId),
            facts = buildMap {
                put("interactionId", interactionId)
                put("actionId", actionId)
                if (!text.isNullOrEmpty()) put("decisionTextHash", "sha256:${CanonicalHash.sha256Hex(text)}")
            },
            idempotencyKey = "human:$interactionId",
            createdAt = null,
        )
        val recorded = when (val result = evidenceRepository.append(scope, namespaceId, workflowId, evidenceItem)) {
            is EvidenceAppendResult.Created -> result.item
            is EvidenceAppendResult.Idempotent -> result.item
            is EvidenceAppendResult.Collision -> throw workflowException(result.code)
        }
        val requestedStatus = if (actionId == "approve") "completed" else "failed"
        val validation = WorkflowTransitionPolicy.validateRequest(
            mapOf(
                "workflowId" to workflowId,
                "stepId" to interaction.stepId,
                "expectedRevision" to workflowRevision,
                "requestedStatus" to requestedStatus,
                "evidenceIds" to listOf(recorded.evidenceId),
                "idempotencyKey" to "human-transition:$interactionId:$actorId",
            ),
            workflowId,
        )
        if (validation is TransitionRequestValidation.Invalid) throw workflowException(WorkflowErrorCodes.INVALID_INTERACTION)
        val request = (validation as TransitionRequestValidation.Valid).request
        val evidence = evidenceRepository.list(scope, namespaceId, workflowId).map { it.toPolicyEvidence() }
        val execution = WorkflowExecution(
            kind = "factory-human",
            runtimeId = "factory-dashboard",
            actorId = actorId,
            namespaceId = namespaceId,
        )
        val decision = WorkflowTransitionPolicy.evaluateHumanResolutionTransition(request, snapshot, definition, evidence, execution)
        if (decision is TransitionDecision.Denied) throw decision.toException()
        val applied = WorkflowTransitionPolicy.applyTransition(snapshot, definition, request)
        if (!repository.updateInstance(scope, namespaceId, workflowId, workflowRevision, applied.toInstance(instance))) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The expected revision is stale.")
        }
        repository.appendTransition(
            scope,
            namespaceId,
            workflowId,
            transitionId = request.requestId,
            request = request,
            fromStepId = interaction.stepId,
            toStepId = interaction.stepId,
            payload = mapOf("kind" to "human_resolution", "actionId" to actionId),
        )
        // Strict CAS verification: the interaction closure must win its
        // compare-and-swap. A stale revision means the interaction was answered
        // concurrently; silently ignoring it would close it twice.
        if (!interactionRepository.update(
                scope,
                namespaceId,
                workflowId,
                interactionId,
                interactionRevision,
                interaction.copy(status = "closed", revision = interactionRevision + 1, payload = interaction.payload + ("response" to mapOf("actionId" to actionId, "actorId" to actorId))),
            )
        ) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The interaction revision is stale.")
        }
        interactionRepository.appendEvent(
            scope,
            namespaceId,
            workflowId,
            HumanInteractionEventRecord(
                eventId = UUID.randomUUID().toString(),
                interactionId = interactionId,
                eventType = "interaction_transitioned",
                actorId = actorId,
                payload = mapOf("evidenceId" to recorded.evidenceId, "revision" to applied.revision),
            ),
        )
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to applied.revision))
        return HumanReplyOutcome(
            interaction = interaction,
            result = WorkflowHttpResult(
                200,
                mapOf(
                    "workflowId" to workflowId,
                    "interactionId" to interactionId,
                    "actorId" to actorId,
                    "evidenceId" to recorded.evidenceId,
                    "revision" to applied.revision,
                    "projection" to applied.projection,
                    "runtimeNotification" to "not-configured",
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Automatic session resumption
    // ------------------------------------------------------------------

    /**
     * Re-runs the DAG after a human checkpoint is resolved.
     *
     * Only DAG-owned `checkpoint` interactions are resumed: the governed
     * frontend flow (`approval`) mutates the instance without per-step DAG
     * states, which the sequencer cannot resume. The repo root is the run-time
     * `repoRoot` when supplied, otherwise resolved from the namespace via AgentOS
     * (falling back to the process working directory) — the same convention as
     * [io.whozoss.factory.agentattempt.service.OutboxDrainWorker].
     */
    private fun resumeCheckpointSession(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interaction: HumanInteractionRecord,
        repoRoot: Path?,
    ) {
        if (interaction.interactionType != CHECKPOINT_INTERACTION_TYPE) return
        val runner = sessionRunService ?: return
        val root = repoRoot ?: resolveRepoRoot(namespaceId)
        logger.debug { "Auto-resuming session $workflowId after checkpoint ${interaction.interactionId}" }
        runner.runSession(scope, namespaceId, workflowId, root)
    }

    /** The namespace repo root, or the process working directory when AgentOS cannot resolve it. */
    private fun resolveRepoRoot(namespaceId: String): Path =
        agentOsProxyClient
            ?.let { client ->
                runCatching { client.resolveRepoRoot(namespaceId, null) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Path::of)
            }
            ?: Path.of(".")

    // ------------------------------------------------------------------
    // Retries (frontend-run surfaces)
    // ------------------------------------------------------------------

    @Transactional
    fun openRetry(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        expectedRevision: Int,
        reasonCode: String,
    ): WorkflowHttpResult {
        val instance = activeInstance(scope, namespaceId, workflowId)
        val snapshot = instance.toSnapshot()
        if (snapshot.stepStatus(stepId) != "blocked") {
            throw workflowException(WorkflowErrorCodes.INTERACTION_STALE, "Only a blocked step can be retried.")
        }
        if (snapshot.revision != expectedRevision) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The expected revision is stale.")
        }
        val interactionId = UUID.randomUUID().toString()
        val interaction = interactionRepository.insert(
            scope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = stepId,
                interactionType = "retry",
                status = "waiting",
                revision = snapshot.revision,
                payload = linkedMapOf(
                    "stepId" to stepId,
                    "prompt" to "Retry step $stepId ($reasonCode)",
                    "actions" to listOf(
                        mapOf("id" to "approve", "label" to "Retry"),
                        mapOf("id" to "reject", "label" to "Keep blocked"),
                    ),
                ),
            ),
        )
        return WorkflowHttpResult(201, mapOf("workflowId" to workflowId, "interaction" to interaction.toJson()))
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Transactional
    fun restore(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowHttpResult {
        val projection = repository.findProjection(scope, namespaceId, workflowId)
        if (projection == null) {
            val instance = repository.findInstance(scope, namespaceId, workflowId) ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
            if (!repository.setInstanceStatus(scope, namespaceId, workflowId, "removed", "active")) {
                throw workflowException(WorkflowErrorCodes.INVALID_LIFECYCLE_TRANSITION)
            }
            sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to instance.revision), WorkflowProjectionEvents.RESTORED)
            return WorkflowHttpResult(200, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "state" to "active", "revision" to instance.revision))
        }
        if (projection.lifecycleState != "removed") throw workflowException(WorkflowErrorCodes.INVALID_LIFECYCLE_TRANSITION)
        if (!repository.setProjectionLifecycle(scope, namespaceId, workflowId, listOf("removed"), "active")) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The projection lifecycle was changed concurrently.")
        }
        sseHub.publish(
            scope,
            namespaceId,
            mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to projection.revision),
            WorkflowProjectionEvents.RESTORED,
        )
        return WorkflowHttpResult(
            200,
            mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "state" to "active", "revision" to projection.revision),
        )
    }

    @Transactional
    fun remove(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowHttpResult {
        val projection = repository.findProjection(scope, namespaceId, workflowId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        if (projection.lifecycleState != "active") throw workflowException(WorkflowErrorCodes.INVALID_LIFECYCLE_TRANSITION)
        if (!repository.setProjectionLifecycle(scope, namespaceId, workflowId, listOf("active"), "removed")) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The projection lifecycle was changed concurrently.")
        }
        repository.setInstanceStatus(scope, namespaceId, workflowId, "active", "removed")
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to projection.revision), WorkflowProjectionEvents.REMOVED)
        return WorkflowHttpResult(200, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "state" to "removed"))
    }

    @Transactional
    fun purge(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowHttpResult {
        val projection = repository.findProjection(scope, namespaceId, workflowId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        if (projection.lifecycleState != "removed") throw workflowException(WorkflowErrorCodes.INVALID_LIFECYCLE_TRANSITION)
        if (!repository.setProjectionLifecycle(scope, namespaceId, workflowId, listOf("removed"), "purged")) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The projection lifecycle was changed concurrently.")
        }
        repository.deleteInstance(scope, namespaceId, workflowId)
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to projection.revision), WorkflowProjectionEvents.PURGED)
        return WorkflowHttpResult(200, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "state" to "purged"))
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun timing(scope: TenantScope, namespaceId: String, workflowId: String): Map<String, Any?> {
        val instance = repository.findInstance(scope, namespaceId, workflowId)
        if (instance == null && repository.findProjection(scope, namespaceId, workflowId) == null) {
            throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        }
        val timestamps = repository.listTransitionTimestamps(scope, namespaceId, workflowId)
        val startedAt = timestamps.firstOrNull()
        val complete = timestamps.isNotEmpty()
        return mapOf(
            "workflowId" to workflowId,
            "namespaceId" to namespaceId,
            "activeMs" to 0L,
            "waitingHumanMs" to 0L,
            "blockedMs" to 0L,
            "attempts" to timestamps.size,
            "startedAt" to startedAt,
            "firstCompletedAt" to null,
            "lastCompletedAt" to null,
            "complete" to complete,
            "incompleteReasons" to if (complete) emptyList<String>() else listOf("no_transitions"),
        )
    }

    @Transactional(readOnly = true)
    fun retries(scope: TenantScope, namespaceId: String, workflowId: String): Map<String, Any?> {
        activeInstance(scope, namespaceId, workflowId)
        val interactions = interactionRepository.list(scope, namespaceId, workflowId, openOnly = false)
        val retryInteractions = interactions.filter { it.interactionType == "retry" }
        return mapOf(
            "workflowId" to workflowId,
            "namespaceId" to namespaceId,
            "retries" to retryInteractions.size,
            "blockedSteps" to emptyList<String>(),
            "openRetryInteractions" to retryInteractions.count { it.status == "waiting" },
        )
    }

    @Transactional(readOnly = true)
    fun metrics(scope: TenantScope, namespaceId: String, workflowId: String, scopeName: String): Map<String, Any?> {
        val timing = timing(scope, namespaceId, workflowId)
        val retries = retries(scope, namespaceId, workflowId)
        val evidence = evidenceRepository.list(scope, namespaceId, workflowId)
        val interactions = interactionRepository.list(scope, namespaceId, workflowId, openOnly = false)
        val realCost = aggregateRealCost(scope, namespaceId, workflowId)
        return mapOf(
            "namespaceId" to namespaceId,
            "workflowId" to workflowId,
            "scope" to scopeName,
            "observedAt" to nowIso(),
            "timing" to timing,
            "retries" to retries,
            "evidenceCount" to evidence.size,
            "interactionCount" to interactions.size,
            "realCost" to realCost.toJson(),
        )
    }

    /** Aggregated real run cost of a workflow, as exposed under `realCost` in [metrics]. */
    private data class RealCostAggregate(
        val cost: Double = 0.0,
        val unknownCostCount: Long = 0L,
        val liveTokens: Long = 0L,
        val paused: Boolean = false,
        val active: Boolean = false,
        val runCostThreshold: Double? = null,
        /** Cases (or blocking ancestors) currently held at a threshold. */
        val pausedCaseIds: List<String> = emptyList(),
    ) {
        fun toJson(): Map<String, Any?> = mapOf(
            "cost" to cost,
            "unknownCostCount" to unknownCostCount,
            "liveTokens" to liveTokens,
            "paused" to paused,
            "active" to active,
            "runCostThreshold" to runCostThreshold,
        )
    }

    /**
     * Root-case resolution of a workflow (trusted boundary only — persisted
     * state, never client input):
     * 1. `controllerExecution.caseId` of the persisted workflow instance,
     * 2. the `caseId` of every persisted durable agent attempt of the workflow.
     *
     * Ids are deduped by exact value: AgentOS already rolls up the whole
     * descendant tree of each case it is asked about (delegations included), so
     * querying each distinct persisted case id once is the faithful root set — a
     * case and one of its own sub-cases must never be counted twice. A requested
     * case id is honoured only when it belongs to the workflow.
     */
    private fun resolveWorkflowCaseIds(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        requestedCaseId: String?,
    ): List<String> {
        val caseIds = LinkedHashSet<String>()
        repository.findInstance(scope, namespaceId, workflowId)?.let { record ->
            ((record.instance["controllerExecution"] as? Map<*, *>)?.get("caseId") as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let(caseIds::add)
        }
        durableAgentAttemptService
            ?.findByWorkflow(scope, namespaceId, workflowId)
            ?.forEach { attempt -> attempt.caseId.takeIf { it.isNotBlank() }?.let(caseIds::add) }
        val requested = requestedCaseId?.takeIf { it.isNotBlank() } ?: return caseIds.toList()
        return if (caseIds.contains(requested)) listOf(requested) else emptyList()
    }

    /**
     * Aggregates the real run cost of the workflow over its distinct case ids,
     * read from AgentOS `GET /api/cases/{caseId}/run-cost`.
     *
     * Degradation is total: a disabled proxy, an unreachable AgentOS, an unknown
     * case or any unexpected error yields the zero aggregate and never breaks
     * `metrics`. `unknownCostCount` is summed verbatim — an unpriced cost is
     * never folded into `cost` as 0.
     */
    @Suppress("UNCHECKED_CAST")
    private fun aggregateRealCost(scope: TenantScope, namespaceId: String, workflowId: String): RealCostAggregate {
        val proxy = agentOsProxyClient ?: return RealCostAggregate()
        return try {
            var aggregate = RealCostAggregate()
            for (caseId in resolveWorkflowCaseIds(scope, namespaceId, workflowId, null)) {
                val runCost = proxy.getRunCost(caseId, null) ?: continue
                val pausedCases = if (runCost.paused) {
                    runCost.pausedCaseIds.ifEmpty { listOf(runCost.caseId) }
                } else {
                    emptyList()
                }
                aggregate = RealCostAggregate(
                    cost = aggregate.cost + runCost.cost,
                    unknownCostCount = aggregate.unknownCostCount + runCost.unknownCostCount,
                    liveTokens = aggregate.liveTokens + runCost.liveTokens,
                    paused = aggregate.paused || runCost.paused,
                    active = aggregate.active || runCost.active,
                    runCostThreshold = maxThreshold(aggregate.runCostThreshold, runCost.runCostThreshold),
                    pausedCaseIds = (aggregate.pausedCaseIds + pausedCases).distinct(),
                )
            }
            aggregate
        } catch (error: Exception) {
            logger.warn(error) { "real-cost aggregation failed for workflow $workflowId; degrading to zero" }
            RealCostAggregate()
        }
    }

    // ------------------------------------------------------------------
    // Governed actions & blockers (single authority)
    // ------------------------------------------------------------------

    /**
     * Authoritative read of what a workflow permits right now.
     *
     * [canReply] reflects the trusted caller's authorization to close a human
     * gate; every other action is gated purely on state. The computation is
     * derived from existing state only (projection steps, open human
     * interactions, durable agent attempts, the real-cost aggregate and audit
     * evidence) — no state is duplicated or recomputed.
     */
    @Transactional(readOnly = true)
    fun workflowActions(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        canReply: Boolean,
    ): WorkflowActionsResponseDto {
        val projectionState = getProjection(scope, namespaceId, workflowId)
        requireExistingWorkflow(projectionState)
        val revision = (projectionState["revision"] as? Number)?.toInt() ?: 0
        val steps = ((projectionState["projection"] as? Map<*, *>)?.get("steps") as? List<*>)
            .orEmpty()
            .mapNotNull { entry -> (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }
        val interactions = interactionRepository.list(scope, namespaceId, workflowId, openOnly = true)
        val attempts = durableAgentAttemptService?.findByWorkflow(scope, namespaceId, workflowId).orEmpty()
        val evidence = evidenceRepository.list(scope, namespaceId, workflowId)
        val realCost = aggregateRealCost(scope, namespaceId, workflowId)

        val actions = mutableListOf<AllowedActionDto>()
        val blockers = mutableListOf<WorkflowBlockerDto>()
        val seenBlockers = mutableSetOf<Pair<String, String?>>()
        fun blocker(code: String, stepId: String?, message: String) {
            if (seenBlockers.add(code to stepId)) blockers.add(WorkflowBlockerDto(code, stepId, message))
        }

        // Open human interactions -> reply action (authorized callers only).
        interactions.forEach { interaction ->
            blocker(
                WorkflowBlockerCodes.WAITING_HUMAN_INTERACTION,
                interaction.stepId,
                "Waiting for a human decision on step '${interaction.stepId}'.",
            )
            if (canReply) {
                actions.add(
                    AllowedActionDto(
                        type = WorkflowActionTypes.REPLY,
                        interactionId = interaction.interactionId,
                        stepId = interaction.stepId,
                        questionEventId = interaction.payload["questionEventId"] as? String,
                        expectedRevision = interaction.revision,
                        label = interaction.payload["prompt"] as? String,
                    ),
                )
            }
        }

        // Projection steps -> blocked steps are retryable, waiting steps are blockers.
        steps.forEach { step ->
            val stepId = step["id"] as? String ?: return@forEach
            when (step["status"] as? String) {
                WorkflowStatuses.BLOCKED -> {
                    blocker(WorkflowBlockerCodes.STEP_BLOCKED, stepId, "Step '$stepId' is blocked.")
                    actions.add(
                        AllowedActionDto(
                            type = WorkflowActionTypes.RETRY,
                            stepId = stepId,
                            expectedRevision = revision,
                            label = "Retry step '$stepId'",
                        ),
                    )
                }

                WorkflowStatuses.WAITING_HUMAN -> blocker(
                    WorkflowBlockerCodes.WAITING_HUMAN_INTERACTION,
                    stepId,
                    "Waiting for a human decision on step '$stepId'.",
                )

                WorkflowStatuses.FAILED -> blocker(
                    WorkflowBlockerCodes.ATTEMPT_FAILED,
                    stepId,
                    "Step '$stepId' failed.",
                )

                else -> Unit
            }
        }

        // Durable attempts -> active attempts are cancellable, terminal ones are blockers.
        attempts.forEach { attempt ->
            if (!attempt.status.terminal) {
                actions.add(
                    AllowedActionDto(
                        type = WorkflowActionTypes.CANCEL_ATTEMPT,
                        stepId = attempt.stepId,
                        attemptId = attempt.attemptId,
                        caseId = attempt.caseId,
                        expectedRevision = attempt.revision,
                        label = "Cancel attempt '${attempt.attemptId}'",
                    ),
                )
            }
            when (attempt.status) {
                AgentAttemptStatus.WAITING_HUMAN -> blocker(
                    WorkflowBlockerCodes.WAITING_HUMAN_INTERACTION,
                    attempt.stepId,
                    "The agent is waiting for a human answer on step '${attempt.stepId}'.",
                )

                AgentAttemptStatus.FAILED -> blocker(
                    WorkflowBlockerCodes.ATTEMPT_FAILED,
                    attempt.stepId,
                    "Execution attempt '${attempt.attemptId}' failed (${attempt.failureCode ?: "unknown"}).",
                )

                AgentAttemptStatus.INDETERMINATE -> blocker(
                    WorkflowBlockerCodes.UNKNOWN_RUNTIME,
                    attempt.stepId,
                    "Execution attempt '${attempt.attemptId}' has an indeterminate outcome.",
                )

                else -> Unit
            }
        }

        // Real-cost pause -> continue/stop actions and a blocker.
        if (realCost.paused) {
            blocker(WorkflowBlockerCodes.REAL_COST_PAUSED, null, "The run is paused: real cost reached its threshold.")
            val pausedCases = realCost.pausedCaseIds.distinct()
            if (pausedCases.isEmpty()) {
                actions.add(
                    AllowedActionDto(WorkflowActionTypes.CONTINUE_COST, expectedRevision = revision, label = "Continue run"),
                )
                actions.add(
                    AllowedActionDto(WorkflowActionTypes.STOP_COST, expectedRevision = revision, label = "Stop run"),
                )
            } else {
                pausedCases.forEach { caseId ->
                    actions.add(
                        AllowedActionDto(
                            type = WorkflowActionTypes.CONTINUE_COST,
                            caseId = caseId,
                            expectedRevision = revision,
                            label = "Continue run",
                        ),
                    )
                    actions.add(
                        AllowedActionDto(
                            type = WorkflowActionTypes.STOP_COST,
                            caseId = caseId,
                            expectedRevision = revision,
                            label = "Stop run",
                        ),
                    )
                }
            }
        }

        // Failed verification evidence -> a verification blocker.
        evidence
            .filter { it.kind == "oracle-result" && it.outcome?.lowercase() in setOf("fail", "failed") }
            .forEach { item ->
                blocker(
                    WorkflowBlockerCodes.VERIFICATION_FAILED,
                    item.stepId,
                    "Verification failed for step '${item.stepId ?: "unknown"}'.",
                )
            }

        return WorkflowActionsResponseDto(allowedActions = actions, blockers = blockers)
    }

    /**
     * Relay a run-cost continuation to AgentOS for every case bound to the
     * workflow, using the trusted caller identity. Fails cleanly with a 503 when
     * usage tracking is disabled/unavailable, a 409 on a stale revision and a
     * 400 when a continuation threshold cannot be resolved.
     */
    fun continueRunCost(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        actorId: String?,
        requestedCaseId: String? = null,
        expectedThreshold: Double? = null,
        expectedRevision: Int? = null,
    ): Map<String, Any?> {
        val proxy = agentOsProxyClient ?: throw UsageTrackingUnavailableException()
        fenceWorkflowRevision(scope, namespaceId, workflowId, expectedRevision)
        val caseIds = resolveWorkflowCaseIds(scope, namespaceId, workflowId, requestedCaseId)
        if (caseIds.isEmpty()) throw workflowException("NO_RUN_CASE", "No AgentOS case is bound to this workflow.")
        var updated = 0
        for (caseId in caseIds) {
            val threshold = expectedThreshold
                ?: proxy.getRunCost(caseId, actorId)?.runCostThreshold
                ?: throw workflowException(
                    WorkflowErrorCodes.INVALID_REQUEST,
                    "expectedThreshold is required to continue a paused run.",
                )
            try {
                if (proxy.continueRunCost(caseId, threshold, actorId)) updated++
            } catch (error: AgentOsUnavailableException) {
                throw UsageTrackingUnavailableException("Usage tracking is disabled", error)
            }
        }
        return mapOf(
            "workflowId" to workflowId,
            "namespaceId" to namespaceId,
            "operation" to "continue",
            "caseIds" to caseIds,
            "updated" to updated,
            "runtimeNotification" to "agentos",
        )
    }

    /**
     * Relay a run-cost stop to AgentOS for every case bound to the workflow.
     * Fails cleanly with a 503 when usage tracking is disabled/unavailable and a
     * 409 on a stale revision.
     */
    fun stopRunCost(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        actorId: String?,
        requestedCaseId: String? = null,
        expectedRevision: Int? = null,
    ): Map<String, Any?> {
        val proxy = agentOsProxyClient ?: throw UsageTrackingUnavailableException()
        fenceWorkflowRevision(scope, namespaceId, workflowId, expectedRevision)
        val caseIds = resolveWorkflowCaseIds(scope, namespaceId, workflowId, requestedCaseId)
        if (caseIds.isEmpty()) throw workflowException("NO_RUN_CASE", "No AgentOS case is bound to this workflow.")
        var updated = 0
        for (caseId in caseIds) {
            try {
                if (proxy.stopRunCost(caseId, actorId)) updated++
            } catch (error: AgentOsUnavailableException) {
                throw UsageTrackingUnavailableException("Usage tracking is disabled", error)
            }
        }
        return mapOf(
            "workflowId" to workflowId,
            "namespaceId" to namespaceId,
            "operation" to "stop",
            "caseIds" to caseIds,
            "updated" to updated,
            "runtimeNotification" to "agentos",
        )
    }

    /** Greatest of two nullable thresholds, ignoring nulls (null when both are). */
    private fun maxThreshold(a: Double?, b: Double?): Double? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    /** Throws the canonical error when [state] is not an existing workflow. */
    private fun requireExistingWorkflow(state: Map<String, Any?>) {
        when (state["state"]) {
            "existing" -> Unit
            "removed" -> throw workflowException(WorkflowErrorCodes.WORKFLOW_REMOVED)
            "purged" -> throw workflowException(WorkflowErrorCodes.WORKFLOW_PURGED)
            else -> throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        }
    }

    /** Revision-fences a cost-control command against the current workflow revision. */
    private fun fenceWorkflowRevision(scope: TenantScope, namespaceId: String, workflowId: String, expectedRevision: Int?) {
        val state = getProjection(scope, namespaceId, workflowId)
        requireExistingWorkflow(state)
        if (expectedRevision == null) return
        val current = (state["revision"] as? Number)?.toInt() ?: 0
        if (expectedRevision != current) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "The expected revision is stale.")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun activeInstance(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowInstanceRecord {
        val instance = repository.findInstance(scope, namespaceId, workflowId)
        if (instance == null) throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        if (instance.status != "active") throw workflowException(WorkflowErrorCodes.WORKFLOW_REMOVED)
        return instance
    }

    private fun resolveDefinition(scope: TenantScope, instance: WorkflowInstanceRecord): WorkflowPolicyDefinition {
        val workflowType = instance.instance["workflowType"] as? String
        val version = instance.instance["definitionVersion"] as? String
        val record = if (workflowType != null && version != null) {
            repository.findDefinition(scope, workflowType, version)
        } else {
            null
        } ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND)
        if (record.definitionHash != instance.instance["definitionHash"]) {
            throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH)
        }
        return record.toPolicyDefinition()
    }

    private companion object {
        /** Interaction type of a DAG-owned human checkpoint (opened by the capability resolver). */
        const val CHECKPOINT_INTERACTION_TYPE = "checkpoint"
        const val MAX_AGENT_ANSWER_LENGTH = 2_000
    }
}

/** Parses a persisted definition into its policy projection. */
@Suppress("UNCHECKED_CAST")
fun WorkflowDefinitionRecord.toPolicyDefinition(): WorkflowPolicyDefinition {
    val rawSteps = definition["steps"] as? List<*> ?: emptyList<Any?>()
    val steps = rawSteps.mapNotNull { entry ->
        val step = (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return@mapNotNull null
        val responsibility = (step["responsibility"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        val kind = ResponsibilityKind.fromWire(responsibility?.get("kind") as? String) ?: return@mapNotNull null
        WorkflowStepDefinition(
            id = step["id"] as String,
            name = step["name"] as String,
            responsibility = WorkflowStepResponsibility(kind, responsibility?.get("name") as? String),
            dependsOn = (step["dependsOn"] as? List<*>)?.map { it as String } ?: emptyList(),
        )
    }
    return WorkflowPolicyDefinition(workflowType = workflowType, version = version, definitionHash = definitionHash, steps = steps)
}

private fun WorkflowInstanceRecord.toSnapshot(): WorkflowSnapshot = WorkflowSnapshot(
    revision = revision,
    governanceMode = instance["governanceMode"] as? String,
    definitionVersion = instance["definitionVersion"] as? String,
    definitionHash = instance["definitionHash"] as? String,
    controllerExecution = (instance["controllerExecution"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value },
    instance = instance,
    projection = projection,
)

@Suppress("UNCHECKED_CAST")
private fun WorkflowSnapshot.stepStatus(stepId: String): String? =
    (instance["steps"] as? List<*>)?.mapNotNull { entry ->
        (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
    }?.firstOrNull { it["id"] == stepId }?.get("status") as? String

private fun WorkflowSnapshot.toInstance(previous: WorkflowInstanceRecord): WorkflowInstanceRecord = WorkflowInstanceRecord(
    namespaceId = previous.namespaceId,
    workflowId = previous.workflowId,
    revision = revision,
    status = previous.status,
    creationCommandHash = previous.creationCommandHash,
    instance = instance,
    projection = projection,
)

private fun TransitionDecision.Denied.toException() = workflowException(code, reason, missingEvidence?.let { mapOf("missingEvidence" to it) })

private fun WorkflowEvidenceItem.toPolicyEvidence(): WorkflowPolicyEvidence = WorkflowPolicyEvidence(
    evidenceId = evidenceId,
    namespaceId = namespaceId,
    workflowId = workflowId,
    stepId = stepId,
    kind = kind,
    outcome = outcome,
    source = source,
    facts = facts,
)

private fun WorkflowEvidenceItem.toJson(): Map<String, Any?> = buildMap {
    put("evidenceId", evidenceId)
    put("namespaceId", namespaceId)
    put("workflowId", workflowId)
    if (stepId != null) put("stepId", stepId)
    put("kind", kind)
    if (outcome != null) put("outcome", outcome)
    if (source != null) put("source", source)
    put("facts", facts)
    if (idempotencyKey != null) put("idempotencyKey", idempotencyKey)
    if (createdAt != null) put("createdAt", createdAt)
}

private fun HumanInteractionRecord.toJson(): Map<String, Any?> = buildMap {
    put("interactionId", interactionId)
    put("workflowId", workflowId)
    put("stepId", stepId)
    put("interactionType", interactionType)
    put("status", status)
    put("revision", revision)
    put("actions", payload["actions"])
    put("prompt", payload["prompt"])
    (payload["response"] as? Map<*, *>)?.let { put("response", it) }
    // Phase 4 ask-step-question: surface the full Q&A and the attempt N -> N+1
    // link so Cockpit can display the question, the audited human answer and
    // the successor attempt of an `agent_question` interaction. Bounded — the
    // payload only carries schema-validated question fields, never secrets.
    if (interactionType == "agent_question") {
        put("namespaceId", namespaceId)
        listOf(
            "attemptId",
            "caseId",
            "questionType",
            "options",
            "recipientRole",
            "contextHash",
            "expiresAt",
            "answer",
            "actorId",
            "answeredAt",
            "successorAttemptId",
        ).forEach { key -> payload[key]?.let { put(key, it) } }
    }
}
