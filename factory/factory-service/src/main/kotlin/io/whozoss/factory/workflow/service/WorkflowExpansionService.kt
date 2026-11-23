package io.whozoss.factory.workflow.service

import io.whozoss.factory.agentattempt.domain.AgentStepResultBusiness
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapability
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.persistence.AgentStepResultRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.CanonicalHash
import io.whozoss.factory.workflow.domain.ContinuationValidation
import io.whozoss.factory.workflow.domain.ContinuationViolation
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WORKFLOW_DEFINITION_SCHEMA_VERSION
import io.whozoss.factory.workflow.domain.WorkflowContinuationCodes
import io.whozoss.factory.workflow.domain.WorkflowContinuationValidator
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepExpansion
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.nowIso
import io.whozoss.factory.workflow.domain.routingJson
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.persistence.EvidenceAppendResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.whozoss.factory.workspace.WorkspacePrecheckPort
import java.util.UUID
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * Run-time expansion of a workflow: an agent step whose definition declares `expansion` may append
 * steps to THAT run only.
 *
 *  - [validateContinuation] checks a continuation when the result is submitted (nothing is stored);
 *  - [graft] adds the accepted steps to the run (instance, projection, step states, evidence). It is
 *    idempotent on the accepted `resultId` and never touches the definition catalog;
 *  - [reconcile] re-runs [graft] after a crash between the step finalization and the addition;
 *  - [expandedDefinitionDraft] returns the base + added steps as a registrable draft.
 *
 * Callers own the transaction boundary of [graft] / [reconcile] (the sequencer wraps them in a short
 * `REQUIRES_NEW` transaction).
 */
@Service
class WorkflowExpansionService(
    private val repository: WorkflowRepository,
    private val evidenceRepository: WorkflowEvidenceRepository,
    private val results: AgentStepResultRepository,
    private val attempts: DurableAgentAttemptService,
    private val sseHub: WorkflowSseHub,
    @param:Autowired(required = false)
    private val precheckPort: WorkspacePrecheckPort? = null,
) {

    private val logger = KotlinLogging.logger {}
    private val resolver = EffectiveWorkflowDefinitionResolver(repository)

    // ------------------------------------------------------------------
    // Validation at submission time
    // ------------------------------------------------------------------

    /** Violations of the continuation carried by [business]; empty when it is acceptable (or absent and not required). */
    fun validateContinuation(
        scope: TenantScope,
        capability: AgentStepResultCapability,
        business: AgentStepResultBusiness,
        resultHash: String,
    ): List<ContinuationViolation> {
        val instance = repository.findInstance(scope, capability.namespaceId, capability.workflowId) ?: return emptyList()
        val base = resolver.baseRecordOrNull(scope, instance) ?: return emptyList()
        val declared = (base.definition["steps"] as? List<*>).orEmpty()
            .mapNotNull { raw -> (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }
            .firstOrNull { it["id"] == capability.stepId }
        val declaresExpansion = WorkflowStepExpansion.parse(declared?.get("expansion")) != null
        val inFragment = parentRunId(instance) != null
        val continuation = business.continuation
        if (continuation == null) {
            return if (declaresExpansion && !inFragment && business.status == AgentStepResultStatus.PASS) {
                listOf(
                    ContinuationViolation(
                        "result.continuation",
                        WorkflowContinuationCodes.CONTINUATION_REQUIRED,
                        "is required on PASS for a step that declares expansion",
                    ),
                )
            } else {
                emptyList()
            }
        }
        if (!declaresExpansion) {
            return listOf(notAllowed("the step does not declare expansion"))
        }
        if (inFragment) {
            return listOf(notAllowed("a fragment of a parent Run cannot add steps"))
        }
        val recorded = resolver.expansionOf(instance)
        if (recorded != null && recorded["resultHash"] != resultHash) {
            return listOf(notAllowed("an expansion is already recorded for this run"))
        }
        val agents = availableAgents(instance)
        return when (val validation = WorkflowContinuationValidator.validate(base.definition, capability.stepId, continuation, agents)) {
            is ContinuationValidation.Valid -> emptyList()
            is ContinuationValidation.Invalid -> validation.violations
        }
    }

    private fun notAllowed(message: String) =
        ContinuationViolation("result.continuation", WorkflowContinuationCodes.CONTINUATION_NOT_ALLOWED, message)

    // ------------------------------------------------------------------
    // Adding the steps to the run
    // ------------------------------------------------------------------

    /**
     * Adds the continuation accepted for [attemptId] to the run. Returns `true` when the steps were
     * added now, `false` when the same result was already added (idempotent replay).
     */
    fun graft(scope: TenantScope, namespaceId: String, workflowId: String, step: WorkflowStepDefinition, attemptId: String): Boolean {
        val instance = repository.findInstance(scope, namespaceId, workflowId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        val result = results.getByAttempt(scope, namespaceId, workflowId, step.id, attemptId)
        val continuation = result?.continuation
        if (result == null || result.status != AgentStepResultStatus.PASS || continuation == null) {
            throw workflowException("EXPANSION_CONTINUATION_MISSING", "The expanding step completed without an accepted continuation.")
        }
        resolver.expansionOf(instance)?.let { recorded ->
            if (recorded["resultId"] == result.resultId) return false
            throw workflowException("EXPANSION_IMMUTABLE", "An expansion is already recorded for this run and cannot be replaced.")
        }
        val base = resolver.baseRecord(scope, instance)
        val valid = when (val validation = WorkflowContinuationValidator.validate(base.definition, step.id, continuation, null)) {
            is ContinuationValidation.Valid -> validation
            is ContinuationValidation.Invalid -> throw workflowException(
                "EXPANSION_INVALID",
                "The accepted continuation no longer validates against the base definition.",
                mapOf("violations" to validation.violations.map { mapOf("path" to it.path, "code" to it.code) }),
            )
        }
        val agentsVerified = availableAgents(instance)?.let { available ->
            valid.steps.filter { it.responsibility.kind == ResponsibilityKind.AGENT }
                .all { it.responsibility.name in available }
        } ?: false
        val now = nowIso()
        val record = linkedMapOf<String, Any?>(
            "sourceStepId" to step.id,
            "attemptId" to attemptId,
            "resultId" to result.resultId,
            "resultHash" to result.resultHash,
            "steps" to valid.normalizedSteps,
            "stepIds" to valid.stepIds,
            "effectiveDefinitionHash" to valid.effectiveDefinitionHash,
            "agentsVerified" to agentsVerified,
            "expandedAt" to now,
        )
        val summary = record - "steps"
        val addedProjection = valid.steps.map { added ->
            linkedMapOf<String, Any?>(
                "id" to added.id,
                "name" to added.name,
                "status" to WorkflowStatuses.PENDING,
                "lane" to added.responsibility.kind.wire,
                "dependsOn" to added.dependsOn,
                "responsibility" to added.responsibility.toJson(),
            ).apply { added.contract?.let { putAll(it.toJson()) }; putAll(added.routingJson()) }
        }
        val nextInstance = instance.instance.toMutableMap()
        nextInstance["expansion"] = record
        nextInstance["steps"] = (nextInstance["steps"] as? List<*>).orEmpty() +
            valid.steps.map { mapOf("id" to it.id, "status" to WorkflowStatuses.PENDING) }
        nextInstance["updatedAt"] = now
        val projection = instance.projection.toMutableMap()
        projection["expansion"] = summary
        projection["steps"] = (projection["steps"] as? List<*>).orEmpty() + addedProjection
        val next = instance.copy(revision = instance.revision + 1, instance = nextInstance, projection = projection)
        nextInstance["revision"] = next.revision
        if (!repository.updateInstance(scope, namespaceId, workflowId, instance.revision, next)) {
            throw workflowException(WorkflowErrorCodes.REVISION_CONFLICT, "Expansion revision conflict.")
        }
        for (added in valid.steps) {
            repository.upsertStepState(
                scope,
                WorkflowStepStateRecord(namespaceId, workflowId, added.id, 1, WorkflowStatuses.PENDING, emptyMap()),
            )
        }
        val appended = evidenceRepository.append(
            scope, namespaceId, workflowId,
            WorkflowEvidenceItem(
                evidenceId = UUID.randomUUID().toString(),
                namespaceId = namespaceId,
                workflowId = workflowId,
                stepId = step.id,
                kind = EVIDENCE_KIND,
                outcome = "pass",
                source = mapOf("kind" to "factory-workflow-expansion"),
                facts = summary,
                idempotencyKey = "$EVIDENCE_KIND:$workflowId:${result.resultId}",
                createdAt = null,
            ),
        )
        check(appended !is EvidenceAppendResult.Collision) { "EXPANSION_EVIDENCE_COLLISION" }
        val previous = repository.findProjection(scope, namespaceId, workflowId)
        repository.publishProjection(
            scope,
            WorkflowProjectionRecord(
                namespaceId = namespaceId,
                workflowId = workflowId,
                schemaVersion = (projection["schemaVersion"] as? String) ?: "2",
                revision = 0,
                projectionHash = CanonicalHash.workflowProjectionHash(projection),
                status = projection["status"] as? String ?: WorkflowStatuses.PENDING,
                projection = projection,
                instance = nextInstance,
                governanceMode = previous?.governanceMode ?: "governed",
                definitionVersion = previous?.definitionVersion ?: instance.instance["definitionVersion"] as? String,
                definitionHash = previous?.definitionHash ?: instance.instance["definitionHash"] as? String,
                relations = previous?.relations,
                controllerExecution = previous?.controllerExecution,
                lifecycleState = "active",
            ),
            expectedRevision = null,
        )
        sseHub.publish(scope, namespaceId, mapOf("workflowId" to workflowId, "namespaceId" to namespaceId))
        logger.info { "Workflow '$workflowId' expanded by step '${step.id}' with ${valid.stepIds.size} step(s)" }
        return true
    }

    /**
     * Crash recovery: when the expanding step is COMPLETED, an accepted continuation exists and no
     * expansion is recorded yet, adds the steps. Returns `true` when steps were added.
     */
    fun reconcile(scope: TenantScope, namespaceId: String, workflowId: String, steps: List<WorkflowStepDefinition>): Boolean {
        val expander = steps.firstOrNull { it.expansion != null } ?: return false
        val instance = repository.findInstance(scope, namespaceId, workflowId) ?: return false
        if (resolver.expansionOf(instance) != null) return false
        val state = repository.findStepStates(scope, namespaceId, workflowId).firstOrNull { it.stepId == expander.id }
        if (state?.status != WorkflowStatuses.COMPLETED) return false
        val attempt = attempts.findLatestForStep(scope, namespaceId, workflowId, expander.id) ?: return false
        val result = results.getByAttempt(scope, namespaceId, workflowId, expander.id, attempt.attemptId)
        if (result == null || result.status != AgentStepResultStatus.PASS || result.continuation == null) return false
        return graft(scope, namespaceId, workflowId, expander, attempt.attemptId)
    }

    // ------------------------------------------------------------------
    // Draft for saving as a reusable definition
    // ------------------------------------------------------------------

    /** The base + added steps as a registrable draft (`expansion` removed). Never registers anything. */
    fun expandedDefinitionDraft(scope: TenantScope, namespaceId: String, workflowId: String): Map<String, Any?> {
        val instance = repository.findInstance(scope, namespaceId, workflowId)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_NOT_FOUND)
        if (instance.status != "active") throw workflowException(WorkflowErrorCodes.WORKFLOW_REMOVED)
        val recorded = resolver.expansionOf(instance)
            ?: throw workflowException("WORKFLOW_NOT_EXPANDED", "This run has no steps added by an expansion.")
        val base = resolver.baseRecord(scope, instance)
        val effective = resolver.resolveRecord(scope, instance)
        val steps = (effective.definition["steps"] as? List<*>).orEmpty().mapNotNull { raw ->
            (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.minus("expansion")
        }
        val title = ((instance.instance["title"] as? String)?.takeIf { it.isNotBlank() } ?: base.definition["title"] as? String ?: base.workflowType)
            .take(MAX_TITLE)
        val draft = linkedMapOf<String, Any?>(
            "schemaVersion" to WORKFLOW_DEFINITION_SCHEMA_VERSION,
            "workflowType" to "${base.workflowType.take(MAX_TYPE_BASE)}$DERIVED_SUFFIX",
            "version" to "1.0.0",
            "title" to title,
        )
        base.definition["trustedExecution"]?.let { draft["trustedExecution"] = it }
        base.definition["execution"]?.let { draft["execution"] = it }
        draft["steps"] = steps
        val valid = when (val validation = WorkflowDefinitionValidator.validate(draft)) {
            is WorkflowDefinitionValidation.Valid -> validation
            is WorkflowDefinitionValidation.Invalid -> throw workflowException(
                WorkflowErrorCodes.WORKFLOW_DEFINITION_INVALID,
                "The expanded definition does not validate.",
                mapOf("path" to validation.error.path, "code" to validation.error.code),
            )
        }
        return linkedMapOf(
            "workflowId" to workflowId,
            "source" to mapOf(
                "workflowType" to base.workflowType,
                "version" to base.version,
                "definitionHash" to base.definitionHash,
            ),
            "expansion" to mapOf(
                "sourceStepId" to recorded["sourceStepId"],
                "attemptId" to recorded["attemptId"],
                "resultId" to recorded["resultId"],
                "resultHash" to recorded["resultHash"],
                "stepIds" to recorded["stepIds"],
                "effectiveDefinitionHash" to recorded["effectiveDefinitionHash"],
            ),
            "definition" to valid.definition,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Enabled agent names of the namespace; `null` when the list cannot be read (never assumed missing). */
    private fun availableAgents(instance: WorkflowInstanceRecord): List<String>? {
        val port = precheckPort ?: return null
        val actor = ((instance.instance["controllerRequest"] as? Map<*, *>)?.get("actorId") as? String)?.takeIf { it.isNotBlank() }
        return runCatching { port.availableAgents(instance.namespaceId, actor) }
            .onFailure { logger.warn(it) { "Agent list of namespace '${instance.namespaceId}' could not be read" } }
            .getOrNull()
    }

    private fun parentRunId(instance: WorkflowInstanceRecord): String? {
        (instance.instance["runId"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        val relations = instance.instance["relations"] as? Map<*, *>
        return (relations?.get("runId") as? String)?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val EVIDENCE_KIND = "workflow-expansion"
        private const val DERIVED_SUFFIX = "-derived"
        private const val MAX_TYPE_BASE = 118
        private const val MAX_TITLE = 256
    }
}
