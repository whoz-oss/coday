package io.whozoss.factory.workflow.service

import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.*
import io.whozoss.factory.workflow.persistence.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Durable correction decisions. All writes join ONE short transaction; no AgentOS/Git calls.
 * The instance CAS serializes decisions, history, invalidation and the outbox wakeup.
 * Only opted-in definitions use this ledger. Existing definitions/runs are not migrated.
 */
@Service
class OutcomeTransitionService(
    private val repository: WorkflowRepository,
    private val evidence: WorkflowEvidenceRepository,
    private val interactions: HumanInteractionRepository,
    private val attempts: DurableAgentAttemptService,
    @param:org.springframework.context.annotation.Lazy
    private val results: AgentStepResultService,
    private val submissions: SessionRunSubmissionService,
) {
    @Transactional
    fun initialize(scope: TenantScope, namespace: String, workflow: String, steps: List<WorkflowStepDefinition>) {
        if (steps.none { it.outcomeTransitions != null }) return
        val instance = requireNotNull(repository.findInstance(scope, namespace, workflow))
        val relations = instance.instance["relations"] as? Map<*, *>
        check(instance.instance["runId"] == null && relations?.get("runId") == null) {
            "OUTCOME_PARENT_FRAGMENT_UNSUPPORTED: parent selectors require an explicit terminal-failure policy before enabling local corrections"
        }
        if (instance.projection.containsKey("stepExecutions")) return
        // Never retrofit an already executed historical run.
        check(repository.findStepStates(scope, namespace, workflow).isEmpty()) { "OUTCOME_LEGACY_RUN_REQUIRES_NEW_RUN" }
        val executions = steps.map { execution(workflow, it.id, 1, 0, null) }
        save(scope, instance, instance.projection + ("stepExecutions" to executions))
        steps.forEach { step ->
            val item = executions.single { it["stepId"] == step.id }
            repository.upsertStepState(scope, WorkflowStepStateRecord(namespace, workflow, step.id, 1,
                if (step.dependsOn.isEmpty()) WorkflowStatuses.READY else WorkflowStatuses.PENDING,
                mapOf("execution" to item)))
        }
    }

    @Transactional
    fun record(scope: TenantScope, namespace: String, workflow: String, step: String,
               executionId: String?, status: String, attemptId: String? = null, evidenceId: String? = null) {
        if (executionId == null) return
        val instance = requireNotNull(repository.findInstance(scope, namespace, workflow))
        val history = history(instance)
        val current = history.lastOrNull { it["stepId"] == step } ?: error("EXECUTION_MISSING")
        check(current["executionId"] == executionId) { "EXECUTION_GENERATION_FENCED" }
        require(WorkflowStatuses.isKnown(status)) { "EXECUTION_STATUS_INVALID" }
        // Recovery may observe an already finalized agent execution. Do not regress its ledger
        // while the scheduler replays the same terminal result without dispatch.
        if (current["status"] in (WorkflowStatuses.TERMINAL + WorkflowStatuses.SKIPPED) && current["status"] != status) {
            check(status == WorkflowStatuses.RUNNING || status == WorkflowStatuses.READY) { "EXECUTION_ALREADY_TERMINAL" }
            return
        }
        val now = nowIso()
        val next = current.toMutableMap()
        next["status"] = status
        val state = repository.findStepStates(scope, namespace, workflow).single { it.stepId == step }
        (state.payload["startedAt"] as? String)?.let { next.putIfAbsent("startedAt", it) }
        if (status == WorkflowStatuses.RUNNING) next.putIfAbsent("startedAt", now)
        if (status in (WorkflowStatuses.TERMINAL + WorkflowStatuses.SKIPPED)) {
            next.putIfAbsent("completedAt", state.payload["completedAt"] as? String ?: now)
        }
        val latest = attempts.findLatestForStep(scope, namespace, workflow, step)
        val boundAttempt = latest?.takeIf { attempt ->
            attempt.contextEnvelope?.let {
                io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope.fromJson(it).inputs["stepExecutionId"]
            } == executionId
        }
        val effectiveAttemptId = attemptId ?: boundAttempt?.attemptId
        if (attemptId != null) check(boundAttempt?.attemptId == attemptId) { "EXECUTION_ATTEMPT_MISMATCH" }
        effectiveAttemptId?.let { next["attemptId"] = it }
        evidenceId?.let { next["resultEvidenceId"] = it }
        check((state.payload["execution"] as? Map<*, *>)?.get("executionId") == executionId) { "EXECUTION_GENERATION_FENCED" }
        repository.upsertStepState(scope, state.copy(payload = state.payload + ("execution" to next)))
        save(scope, instance, instance.projection + ("stepExecutions" to history.map { if (it["executionId"] == executionId) next else it }))
    }

    /** Called before dependency evaluation, including after a restart between finalization and routing. */
    @Transactional
    fun advance(scope: TenantScope, namespace: String, workflow: String, steps: List<WorkflowStepDefinition>,
                repoRoot: String, ticket: String?): Boolean {
        val instance = requireNotNull(repository.findInstance(scope, namespace, workflow))
        if (!instance.projection.containsKey("stepExecutions")) return false
        check(instance.status == "active") { "OUTCOME_WORKFLOW_REMOVED" }
        val correction = instance.projection["correction"] as? Map<*, *>
        if (correction?.get("status") == "waiting_human") return false
        val states = repository.findStepStates(scope, namespace, workflow).associateBy { it.stepId }
        for (step in steps) {
            val policy = step.outcomeTransitions?.fail?.takeIf { it.action == "return" } ?: continue
            val state = states[step.id] ?: continue
            if (state.status != WorkflowStatuses.FAILED) continue
            val current = history(instance).lastOrNull { it["stepId"] == step.id } ?: continue
            val trigger = when (step.responsibility.kind) {
                ResponsibilityKind.AGENT -> agentTrigger(scope, namespace, workflow, step, current)
                ResponsibilityKind.CODE -> ciTrigger(scope, namespace, workflow, step, current)
                ResponsibilityKind.HUMAN -> null
            } ?: continue
            val handled = (instance.projection["outcomeDecisions"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            if (trigger.key in handled) continue
            val target = requireNotNull(policy.target)
            val replay = OutcomeReplayPlan.scope(steps, target)
            check(replay.none { states[it]?.status in CORRECTION_SCOPE_ACTIVE }) { "CORRECTION_SCOPE_ACTIVE" }
            check(attempts.findByWorkflow(scope, namespace, workflow).none { it.stepId in replay && !it.status.terminal }) { "CORRECTION_ATTEMPT_ACTIVE" }
            val regionKey = CanonicalHash.sha256Hex(replay.sorted().joinToString("\n"))
            val counters = (instance.projection["correctionCounts"] as? Map<*, *>)?.entries
                ?.associate { it.key.toString() to ((it.value as Number).toInt()) }.orEmpty()
            val cycle = counters[regionKey] ?: 0
            val max = policy.maxCycles ?: 2
            val metadata = linkedMapOf<String, Any?>(
                "cycleId" to id("cycle:$workflow:${trigger.key}"), "cycleNumber" to cycle,
                "maxCycles" to max, "status" to if (cycle >= max) "waiting_human" else "active",
                "triggeringStepId" to step.id, "triggeringAttemptId" to trigger.attemptId,
                "triggeringExecutionId" to current["executionId"], "returnTargetStepId" to target,
                "resultId" to trigger.key,
            )
            val relevantEvidence = evidence.list(scope, namespace, workflow).filter {
                (trigger.attemptId != null && it.facts["attemptId"] == trigger.attemptId) ||
                    (trigger.resultEvidenceId != null && it.evidenceId == trigger.resultEvidenceId) ||
                    it.kind in setOf("branch-preparation", "code-verification")
            }
            val context = mapOf(
                "rejectedResultId" to trigger.key, "rejectedAttemptId" to trigger.attemptId,
                "summary" to trigger.summary, "findings" to trigger.findings, "artifacts" to trigger.artifacts,
                "claims" to trigger.claims,
                // References only: historical PASS must not be mistaken for current dependency input.
                "priorHandoffEvidenceIds" to evidence.list(scope, namespace, workflow).filter {
                    it.kind == "agent-result" && it.outcome == "pass" && it.stepId in replay
                }.takeLast(20).map { it.evidenceId },
                "evidenceIds" to relevantEvidence.map { it.evidenceId },
                "workspaceEvidence" to relevantEvidence.filter { it.kind in setOf("workspace-delta", "branch-preparation", "code-verification") }.takeLast(20).map { it.facts },
                "relations" to instance.instance["relations"],
                "instruction" to "Correct the rejected work in the existing worktree/branch/PR. Read the referenced review and workspace evidence, verify reviewed HEAD against current HEAD, retain the frozen base. Do not initialize a new branch or duplicate the PR. Missing or inconsistent provenance requires human clarification, not reset." +
                    if (trigger.ci) " The CI of the pull request failed; analyse the attached failed checks and logs and correct the plan." else "",
            )
            var projection = instance.projection + mapOf("correction" to metadata, "outcomeDecisions" to (handled + trigger.key), "correctionContext" to context)
            if (cycle >= max) {
                // Intervention is deliberately not an auto-approved governed retry.
                val interactionId = id("correction-human:$workflow:${trigger.key}")
                interactions.insert(scope, HumanInteractionRecord(interactionId, namespace, workflow, step.id,
                    "correction_exhausted", "waiting", 1, mapOf(
                        "prompt" to "Correction budget exhausted ($max cycles). Inspect evidence and start an explicitly governed successor run if further work is needed.",
                        "actions" to emptyList<Map<String, String>>(),
                        "executionId" to current["executionId"], "correction" to metadata)))
                projection = projection + ("status" to WorkflowStatuses.WAITING_HUMAN)
            } else {
                metadata["cycleNumber"] = cycle + 1
                projection = projection + ("correctionCounts" to (counters + (regionKey to cycle + 1)))
                val history = history(instance).map { item ->
                    if (item["executionId"] != current["executionId"]) item else item + mapOf(
                        "status" to WorkflowStatuses.FAILED, "attemptId" to (trigger.attemptId ?: item["attemptId"]),
                        "completedAt" to (trigger.completedAt ?: nowIso()), "resultEvidenceId" to trigger.resultEvidenceId)
                }.toMutableList()
                for (replayed in steps.filter { it.id in replay }) {
                    val number = history.count { it["stepId"] == replayed.id } + 1
                    val next = execution(workflow, replayed.id, number, cycle + 1, current["executionId"] as String)
                    history += next
                    repository.upsertStepState(scope, WorkflowStepStateRecord(namespace, workflow, replayed.id, 1,
                        WorkflowStatuses.PENDING, mapOf("execution" to next)))
                }
                val projectedSteps = (projection["steps"] as? List<*>).orEmpty().map { raw ->
                    val item = (raw as Map<*, *>).entries.associate { it.key.toString() to it.value }
                    if (item["id"] !in replay) item else (item - setOf("startedAt", "completedAt", "durationMs", "routeDecision", "activation", "executed", "waitingQuestion")) + mapOf(
                        "status" to WorkflowStatuses.PENDING,
                        "executionId" to history.last { it["stepId"] == item["id"] }["executionId"])
                }
                projection = projection + mapOf("stepExecutions" to history, "steps" to projectedSteps, "status" to WorkflowStatuses.READY)
                submissions.submit(scope, namespace, workflow, repoRoot, "outcome-cycle:${metadata["cycleId"]}", ticket)
            }
            save(scope, instance, projection)
            val appended = evidence.append(scope, namespace, workflow, WorkflowEvidenceItem(
                id("outcome-evidence:$workflow:${trigger.key}"), namespace, workflow, step.id, "outcome-transition",
                metadata["status"] as String, mapOf("kind" to "factory-outcome-router"), metadata.toMap(),
                "outcome:${trigger.key}", null))
            check(appended !is EvidenceAppendResult.Collision) { "OUTCOME_DECISION_COLLISION" }
            return true
        }
        return false
    }

    /**
     * What made a step eligible for `fail.return`. [key] is the dedup identity of the decision (an accepted result id,
     * or `ci:<evidenceId>` for a CI verdict), so a replayed advance never opens a second cycle for the same failure.
     */
    private class Trigger(
        val key: String,
        val attemptId: String?,
        val resultEvidenceId: String?,
        val completedAt: String?,
        val summary: String,
        val findings: List<Any?>,
        val artifacts: List<Any?>,
        val claims: Any?,
        val ci: Boolean = false,
    )

    /** Eligible agent trigger: the exact finalized, accepted business FAIL of the step's current execution. */
    private fun agentTrigger(
        scope: TenantScope, namespace: String, workflow: String, step: WorkflowStepDefinition, current: Map<String, Any?>,
    ): Trigger? {
        val latest = attempts.findLatestForStep(scope, namespace, workflow, step.id) ?: return null
        // FAILED includes runtime errors. Only the exact finalized, accepted business FAIL is eligible.
        if (latest.status != AgentAttemptStatus.FAILED || latest.failureCode != "FACTORY_STEP_RESULT_FAILED") return null
        val result = results.acceptedResult(scope, namespace, workflow, step.id, latest.attemptId) ?: return null
        if (result.status != AgentStepResultStatus.FAIL) return null
        val pinnedExecution = latest.contextEnvelope?.let {
            io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope.fromJson(it).inputs["stepExecutionId"]
        }
        if (pinnedExecution != current["executionId"]) return null
        if (current["attemptId"] != null && current["attemptId"] != latest.attemptId) return null
        return Trigger(
            key = result.resultId, attemptId = latest.attemptId, resultEvidenceId = latest.resultEvidenceId,
            completedAt = latest.completedAt?.toString(), summary = result.summary,
            findings = result.findings, artifacts = result.artifacts, claims = result.claims,
        )
    }

    /**
     * Eligible CI trigger: the `github-pr-checks` code step failed with a REAL red verdict recorded for its current
     * execution. A timeout, a refusal (missing token, draft PR, no PR, auth error) or the evidence of an older
     * execution leaves no such verdict, so the step blocks and no correction budget is consumed.
     */
    private fun ciTrigger(
        scope: TenantScope, namespace: String, workflow: String, step: WorkflowStepDefinition, current: Map<String, Any?>,
    ): Trigger? {
        if (step.responsibility.name != OutcomeReplayPlan.CI_RETURN_CAPABLE) return null
        val verdict = evidence.list(scope, namespace, workflow, step.id).lastOrNull { it.kind == CI_CHECKS_EVIDENCE_KIND } ?: return null
        if (verdict.outcome != "fail" || verdict.facts["verdict"] != "failed" || verdict.facts["executionId"] != current["executionId"]) return null
        val failed = (verdict.facts["failedChecks"] as? List<*>).orEmpty().mapNotNull { raw ->
            (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }
        val names = failed.mapNotNull { it["name"] as? String }
        val logs = failed.joinToString("\n\n") { check ->
            buildString {
                appendLine("## ${check["name"]} (${check["conclusion"]})")
                (check["url"] as? String)?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                (check["summary"] as? String)?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                (check["logExcerpt"] as? String)?.takeIf { it.isNotBlank() }?.let {
                    appendLine("```")
                    appendLine(it)
                    appendLine("```")
                }
            }
        }
        val artifact = boundedArtifact(logs)
        return Trigger(
            key = "ci:${verdict.evidenceId}", attemptId = null, resultEvidenceId = verdict.evidenceId, completedAt = null,
            summary = "CI failed on PR #${verdict.facts["prNumber"]} (${(verdict.facts["headSha"] as? String)?.take(8)}): ${names.joinToString()}",
            findings = failed.map { check ->
                mapOf("severity" to "error", "code" to "CI_CHECK_FAILED", "summary" to "${check["name"]}: ${check["conclusion"]}")
            },
            artifacts = if (artifact.isEmpty()) emptyList() else listOf(mapOf("kind" to "ci-failure-logs", "encoding" to "markdown", "content" to artifact)),
            claims = emptyMap<String, Any?>(), ci = true,
        )
    }

    /** Keeps a markdown artifact within the 256 KiB bound of the worker result schema. */
    private fun boundedArtifact(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= MAX_ARTIFACT_BYTES) content else String(bytes, 0, MAX_ARTIFACT_BYTES, Charsets.UTF_8).trimEnd('\uFFFD')
    }

    private companion object {
        /** A correction scope with a step in one of these states must not be replayed. */
        val CORRECTION_SCOPE_ACTIVE = setOf(
            WorkflowStatuses.RUNNING, WorkflowStatuses.WAITING_HUMAN, WorkflowStatuses.NEEDS_RESEARCH, WorkflowStatuses.WAITING_EXTERNAL,
        )
        const val CI_CHECKS_EVIDENCE_KIND = "ci-checks"
        const val MAX_ARTIFACT_BYTES = 262_144
    }

    private fun history(instance: WorkflowInstanceRecord): List<Map<String, Any?>> =
        (instance.projection["stepExecutions"] as? List<*>).orEmpty().map { raw ->
            (raw as Map<*, *>).entries.associate { it.key.toString() to it.value }
        }

    private fun execution(workflow: String, step: String, number: Int, cycle: Int, trigger: String?) = buildMap<String, Any?> {
        put("executionId", id("execution:$workflow:$step:$number")); put("stepId", step)
        put("executionNumber", number); put("cycleNumber", cycle); put("status", WorkflowStatuses.PENDING)
        trigger?.let { put("triggeringExecutionId", it) }
    }

    private fun save(scope: TenantScope, instance: WorkflowInstanceRecord, projection: Map<String, Any?>) {
        val body = instance.instance + mapOf("revision" to instance.revision + 1,
            "status" to (projection["status"] ?: instance.instance["status"]),
            "steps" to ((projection["steps"] as? List<*>).orEmpty().map { raw ->
                val step = raw as Map<*, *>; mapOf("id" to step["id"], "status" to step["status"])
            }))
        check(repository.updateInstance(scope, instance.namespaceId, instance.workflowId, instance.revision,
            instance.copy(revision = instance.revision + 1, instance = body, projection = projection))) { "OUTCOME_REVISION_CONFLICT" }
        val previous = repository.findProjection(scope, instance.namespaceId, instance.workflowId)
        repository.publishProjection(scope, WorkflowProjectionRecord(instance.namespaceId, instance.workflowId,
            projection["schemaVersion"] as? String ?: "2", 0, CanonicalHash.workflowProjectionHash(projection),
            projection["status"] as? String ?: WorkflowStatuses.PENDING, projection, body, "governed",
            instance.instance["definitionVersion"] as? String, instance.instance["definitionHash"] as? String,
            previous?.relations, previous?.controllerExecution, "active"), null).let {
            check(it !is ProjectionPublishResult.Conflict) { "OUTCOME_PROJECTION_CONFLICT" }
        }
    }

    private fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}
