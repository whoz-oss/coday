package io.whozoss.factory.workflow.service

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.domain.CanonicalHash
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.TransitionDecision
import io.whozoss.factory.workflow.domain.TransitionRequestValidation
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
import org.springframework.transaction.annotation.Transactional
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
) {

    private val logger = KotlinLogging.logger {}

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
        if (record.controllerExecution != null) put("controllerExecution", record.controllerExecution)
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
        (record.instance["controllerExecution"] as? Map<*, *>)?.let { put("controllerExecution", it) }
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
                sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to result.record.revision))
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
        sseHub.publish(namespaceId, mapOf("workflowId" to command.workflowId, "namespaceId" to namespaceId, "revision" to 1))
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
        sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to applied.revision))
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
        sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to applied.revision))
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
        sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to applied.revision))
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
     * Atomic human reply: interaction update -> audited `human-decision` evidence
     * -> governed transition -> interaction closure, all in ONE transaction.
     */
    @Transactional
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
        interactionRepository.update(
            scope,
            namespaceId,
            workflowId,
            interactionId,
            interactionRevision,
            interaction.copy(status = "closed", revision = interactionRevision + 1, payload = interaction.payload + ("response" to mapOf("actionId" to actionId, "actorId" to actorId))),
        )
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
        sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "revision" to applied.revision))
        // Auto human resumption: re-trigger the in-process DAG sequencer so the
        // downstream ready steps run without the cockpit calling `/continue`.
        resumeCheckpointSession(scope, namespaceId, workflowId, interaction, repoRoot)
        return WorkflowHttpResult(
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
            sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId), WorkflowProjectionEvents.RESTORED)
            return WorkflowHttpResult(200, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "state" to "active", "revision" to instance.revision))
        }
        if (projection.lifecycleState != "removed") throw workflowException(WorkflowErrorCodes.INVALID_LIFECYCLE_TRANSITION)
        repository.setProjectionLifecycle(scope, namespaceId, workflowId, listOf("removed"), "active")
        sseHub.publish(
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
        repository.setProjectionLifecycle(scope, namespaceId, workflowId, listOf("active"), "removed")
        repository.setInstanceStatus(scope, namespaceId, workflowId, "active", "removed")
        sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId), WorkflowProjectionEvents.REMOVED)
        return WorkflowHttpResult(200, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId, "state" to "removed"))
    }

    @Transactional
    fun purge(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowHttpResult {
        val projection = repository.findProjection(scope, namespaceId, workflowId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        if (projection.lifecycleState != "removed") throw workflowException(WorkflowErrorCodes.INVALID_LIFECYCLE_TRANSITION)
        repository.setProjectionLifecycle(scope, namespaceId, workflowId, listOf("removed"), "purged")
        repository.deleteInstance(scope, namespaceId, workflowId)
        sseHub.publish(namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId), WorkflowProjectionEvents.PURGED)
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
        return mapOf(
            "namespaceId" to namespaceId,
            "workflowId" to workflowId,
            "scope" to scopeName,
            "observedAt" to nowIso(),
            "timing" to timing,
            "retries" to retries,
            "evidenceCount" to evidence.size,
            "interactionCount" to interactions.size,
        )
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
}
