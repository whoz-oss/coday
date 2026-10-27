package io.whozoss.factory.forge.domain

import io.whozoss.factory.workflow.domain.ProjectionValidation
import io.whozoss.factory.workflow.domain.WorkflowProjectionValidator
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Pure Forge workflow adapter: deterministically transform one normalized
 * `readForgeRunYaml()` record into the generic workflow projection understood
 * by the dashboard and the workflow-projection store.
 *
 * Port of `factory/src/domain/forge-bmad/forge-workflow-adapter.ts`. The
 * projection validator is the Kotlin port of `factory/lib/workflow-projection.mjs`
 * ([WorkflowProjectionValidator]).
 */
object ForgeWorkflowAdapter {

    /** Machine codes returned by the Forge workflow adapter. */
    const val INVALID_RUN = "INVALID_FORGE_RUN"
    const val UNKNOWN_DECISION = "UNKNOWN_FORGE_DECISION"
    const val UNKNOWN_OUTCOME = "UNKNOWN_FORGE_OUTCOME"
    const val IMPOSSIBLE_GATE_ORDER = "IMPOSSIBLE_FORGE_GATE_ORDER"
    const val INVALID_PROJECTION = "INVALID_FORGE_PROJECTION"

    private val DECISIONS = mapOf(
        "approved" to "completed",
        "approved-with-changes" to "completed",
        "rejected" to "failed",
    )

    private data class Gate(val source: String, val id: String, val name: String)

    private val GATES = listOf(
        Gate("gate_1", "gate-1", "Ticket"),
        Gate("gate_2", "gate-2", "Spec"),
        Gate("gate_3", "gate-3", "Tech Review"),
        Gate("gate_4", "gate-4", "Func Review"),
    )

    private val TICKET = Regex("^[A-Z][A-Z0-9]+-\\d+$")

    private fun failure(code: String, path: String, details: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf("ok" to false, "error" to mapOf("code" to code, "path" to path, "details" to details))

    /** Parse an instant, returning epoch millis or null when unparseable. */
    internal fun instantMillis(value: Any?): Long? {
        if (value !is String || value.isBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun validInstant(value: Any?): Boolean =
        value == null || (value is String && value.trim().isNotEmpty() && instantMillis(value) != null)

    private class GateState(
        val ok: Boolean,
        val status: String? = null,
        val started: Boolean = false,
        val terminal: Boolean = false,
        val error: Map<String, Any?>? = null,
    )

    private fun gateStatus(gate: Any?, path: String): GateState {
        val map = gate as? Map<*, *> ?: return GateState(ok = true, status = "pending", started = false, terminal = false)
        val startedAt = map["startedAt"]
        val decidedAt = map["decidedAt"]
        if (startedAt != null && !validInstant(startedAt)) {
            return invalid("$path.startedAt", "invalid_timestamp")
        }
        if (decidedAt != null && !validInstant(decidedAt)) {
            return invalid("$path.decidedAt", "invalid_timestamp")
        }
        if (decidedAt != null && startedAt == null) {
            return invalid("$path.decidedAt", "decided_without_start")
        }
        if (startedAt != null && decidedAt != null && instantMillis(decidedAt)!! < instantMillis(startedAt)!!) {
            return invalid("$path.decidedAt", "decision_before_start")
        }
        val decision = map["humanDecision"]
        if (decision != null) {
            val status = DECISIONS[decision.toString()]
                ?: return GateState(
                    ok = false,
                    error = mapOf(
                        "code" to UNKNOWN_DECISION,
                        "path" to "$path.humanDecision",
                        "details" to mapOf("value" to decision),
                    ),
                )
            if (startedAt == null || decidedAt == null) {
                return invalid(path, "decision_without_complete_timestamps")
            }
            return GateState(ok = true, status = status, started = true, terminal = true)
        }
        if (decidedAt != null) return invalid("$path.decidedAt", "decision_timestamp_without_decision")
        return GateState(ok = true, status = if (startedAt != null) "running" else "pending", started = startedAt != null)
    }

    private fun invalid(path: String, reason: String): GateState =
        GateState(ok = false, error = mapOf("code" to INVALID_RUN, "path" to path, "details" to mapOf("reason" to reason)))

    /** Deterministically adapt one normalized readForgeRunYaml() record. */
    @Suppress("UNCHECKED_CAST")
    fun adapt(run: Any?): Map<String, Any?> {
        val record = run as? Map<*, *>
        val ticketId = record?.get("ticketId") as? String
        if (record == null || ticketId == null || !TICKET.matches(ticketId)) {
            return failure(INVALID_RUN, "ticketId")
        }
        val states = mutableListOf<GateState>()
        val gates = record["gates"] as? Map<*, *>
        for (gate in GATES) {
            val state = gateStatus(gates?.get(gate.source), "gates.${gate.source}")
            if (!state.ok) return mapOf("ok" to false, "error" to state.error!!)
            states.add(state)
        }
        for (index in 1 until states.size) {
            if (states[index].started && states[index - 1].status != "completed") {
                return failure(
                    IMPOSSIBLE_GATE_ORDER,
                    "gates.${GATES[index].source}",
                    mapOf("precedingGate" to GATES[index - 1].source),
                )
            }
        }
        val runOutcome = record["runOutcome"] as? Map<*, *>
        val outcome = runOutcome?.get("status")
        if (outcome !in listOf("in-progress", "completed", "abandoned")) {
            return failure(UNKNOWN_OUTCOME, "runOutcome.status", mapOf("value" to outcome))
        }
        if (outcome == "completed" && states.any { it.status != "completed" }) {
            return failure(INVALID_RUN, "runOutcome.status", mapOf("reason" to "completed_before_all_gates_approved"))
        }
        if (outcome == "in-progress" && states.all { it.status == "completed" }) {
            return failure(INVALID_RUN, "runOutcome.status", mapOf("reason" to "all_gates_complete_but_run_in_progress"))
        }

        val status = when {
            outcome == "completed" -> "completed"
            outcome == "abandoned" -> "cancelled"
            states.any { it.status == "failed" } -> "failed"
            states.any { it.status == "running" } -> "running"
            states.any { it.status == "completed" } -> "ready"
            else -> "pending"
        }

        val ticketSummary = record["ticketSummary"] as? String
        val candidate = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowId" to "forge-run-$ticketId",
            "workflowType" to "forge-ticket-v1",
            "title" to (ticketSummary?.trim()?.takeIf { it.isNotEmpty() } ?: ticketId),
            "status" to status,
            "steps" to GATES.mapIndexed { index, gate ->
                linkedMapOf<String, Any?>(
                    "id" to gate.id,
                    "name" to gate.name,
                    "status" to if (outcome == "abandoned" && !states[index].terminal) "cancelled" else states[index].status,
                    "dependsOn" to if (index == 0) emptyList<String>() else listOf(GATES[index - 1].id),
                )
            },
        )
        return when (val validated = WorkflowProjectionValidator.validate(candidate, "forge-run-$ticketId")) {
            is ProjectionValidation.Valid -> mapOf("ok" to true, "projection" to validated.normalized)
            is ProjectionValidation.Invalid -> failure(
                INVALID_PROJECTION,
                validated.error.path,
                mapOf(
                    "validation" to mapOf(
                        "code" to validated.error.code,
                        "path" to validated.error.path,
                        "details" to validated.error.details,
                    ),
                ),
            )
        }
    }
}

fun adaptForgeRunToWorkflowProjection(run: Any?): Map<String, Any?> = ForgeWorkflowAdapter.adapt(run)
