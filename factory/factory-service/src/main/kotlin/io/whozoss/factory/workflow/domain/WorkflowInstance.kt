package io.whozoss.factory.workflow.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pure workflow-instance materialization.
 *
 * Faithful port of `factory/src/domain/workflow/workflow-instance.ts`:
 * [workflowStartCommandHash] and [createWorkflowInstance] build the governed
 * instance (revision 1) and its WorkflowProjection v2 from a validated
 * definition. Only `java.security`/`java.time` are used — no I/O.
 */

/** The materialized instance + projection of a start command. */
data class CreatedWorkflowInstance(
    val instance: Map<String, Any?>,
    val projection: Map<String, Any?>,
    val creationCommandHash: String,
)

/** ISO-8601 instant with millisecond precision + `Z`, matching JS `toISOString()`. */
fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

/**
 * Independent relations: a workflow with no explicit relations is its own root.
 */
fun independentWorkflowRelations(workflowId: String): Map<String, Any?> = mapOf("rootWorkflowId" to workflowId)

/**
 * Relations of a SUCCESSOR workflow that resumes or re-runs a sealed
 * (terminal-run) predecessor (Phase 10 terminal governance).
 *
 * A terminal workflow (`completed` / `failed` / `cancelled`) is immutable and
 * sealed: reopening it is strictly forbidden (`WORKFLOW_SEALED`). To resume
 * or re-run the requirement, the control plane starts a NEW [newWorkflowId]
 * whose [WorkflowStartCommand.relations] carry [previousWorkflowId] (the
 * sealed predecessor) and the shared [rootWorkflowId] — by default the
 * predecessor itself is the root of the run chain; pass the predecessor's own
 * `rootWorkflowId` to keep a longer chain under one root. The sealed
 * predecessor is never reopened, mutated or re-linked.
 *
 * Relations already flow unchanged through [createWorkflowInstance] and
 * [CanonicalHash.workflowStartCommandHash], so no start-command change is
 * required: pass the returned map as `WorkflowStartCommand.relations`.
 */
fun linkedWorkflowRelations(
    newWorkflowId: String,
    previousWorkflowId: String,
    rootWorkflowId: String = previousWorkflowId,
): Map<String, Any?> = mapOf(
    "rootWorkflowId" to rootWorkflowId,
    "previousWorkflowId" to previousWorkflowId,
)

/**
 * Builds the governed instance and its v2 projection for a validated start
 * command, and computes the creation command hash.
 */
fun createWorkflowInstance(
    command: WorkflowStartCommand,
    definition: WorkflowDefinitionInput,
    controllerExecution: ControllerExecutionInput,
    observedAt: String = nowIso(),
): CreatedWorkflowInstance {
    val steps = definition.steps.map { step ->
        linkedMapOf<String, Any?>(
            "id" to step.id,
            "name" to step.name,
            "status" to if (step.dependsOn.isEmpty()) WorkflowStatuses.READY else WorkflowStatuses.PENDING,
            "lane" to step.responsibility.kind.wire,
            "dependsOn" to step.dependsOn.toList(),
            "responsibility" to step.responsibility.toJson(),
        )
    }
    val relations = command.relations?.toMap() ?: independentWorkflowRelations(command.workflowId)
    val instance = linkedMapOf<String, Any?>(
        "governanceMode" to WORKFLOW_GOVERNANCE_MODE,
        "workflowId" to command.workflowId,
        "workflowType" to definition.workflowType,
        "definitionVersion" to definition.version,
        "definitionHash" to definition.definitionHash,
        "revision" to 1,
        "title" to command.title,
        "status" to WorkflowStatuses.READY,
        "steps" to steps.map { linkedMapOf<String, Any?>("id" to it["id"], "status" to it["status"]) },
        "relations" to relations,
        "controllerExecution" to (controllerExecution.copy(observedAt = observedAt).toJson()),
        "environmentRef" to null,
        "deliveryRef" to null,
        "createdAt" to observedAt,
        "updatedAt" to observedAt,
    )
    // The optional ticket travels with the instance so every later run/resume
    // (which only receives the workflowId) can still reach the agent brief and
    // the branch-naming relations.
    if (!command.ticket.isNullOrBlank()) {
        instance["ticket"] = command.ticket
    }
    command.controllerRequest?.let { instance["controllerRequest"] = it.toJson() }
    val projection = linkedMapOf<String, Any?>(
        "schemaVersion" to "2",
        "workflowId" to command.workflowId,
        "workflowType" to definition.workflowType,
        "title" to command.title,
        "status" to WorkflowStatuses.READY,
        "steps" to steps,
    )
    command.controllerRequest?.let { projection["controllerRequest"] = it.toJson() }
    // Optional execution-plugin selection: carried by both the governed instance
    // and its projection so downstream consumers can attribute the run to the
    // declared plugin. Absent for definitions without an `execution` block, which
    // keeps the historical instance/projection shape (and hashes) unchanged.
    definition.executionPolicy?.let { policy ->
        val execution = policy.toJson()
        instance["execution"] = execution
        projection["execution"] = execution
    }
    return CreatedWorkflowInstance(
        instance = instance,
        projection = projection,
        creationCommandHash = CanonicalHash.workflowStartCommandHash(command, definition),
    )
}
