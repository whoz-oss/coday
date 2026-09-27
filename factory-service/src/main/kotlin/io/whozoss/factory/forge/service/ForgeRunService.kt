package io.whozoss.factory.forge.service

import io.whozoss.factory.forge.domain.ForgeCodedException
import io.whozoss.factory.forge.domain.ForgeHumanDecision
import io.whozoss.factory.forge.domain.ForgeLedger
import io.whozoss.factory.forge.domain.REPO_RUN_STORE_POLICY
import io.whozoss.factory.forge.domain.asInt
import io.whozoss.factory.forge.domain.asMap
import io.whozoss.factory.forge.domain.defaultRunStoreRoot
import io.whozoss.factory.forge.infrastructure.ForgeRootsResolver
import io.whozoss.factory.forge.port.CreateEpicRunRequest
import io.whozoss.factory.forge.port.ForgeLedgerStore
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** A resolved command to create an EpicRun ledger. */
data class CreateForgeRunCommand(
    val roots: Map<String, Any?>,
    val epic: Map<String, Any?>,
    val stories: List<Map<String, Any?>>,
    val runId: String? = null,
)

/**
 * High-level Forge run orchestration.
 *
 * Port of `factory/dashboard/forge-routes.mjs` for run creation, projection
 * listing and G1 human-decision recording; the deterministic gate logic lives in
 * [ForgeGateService].
 */
@Service
class ForgeRunService(
    private val ledgerStore: ForgeLedgerStore,
) {

    /** Create the EpicRun ledger from a validated request body. */
    fun createEpicRun(command: CreateForgeRunCommand, defaultOrchestratorRoot: String): Map<String, Any?> {
        val repoRoot = command.roots["repoRoot"] as? String
            ?: throw ForgeCodedException("INVALID_FORGE_RUN_REQUEST", "roots.repoRoot is required")
        val epicId = command.epic["id"] as? String
        val epicKind = command.epic["kind"] as? String
        if (epicId.isNullOrBlank() || epicKind.isNullOrBlank()) {
            throw ForgeCodedException("INVALID_FORGE_RUN_REQUEST", "epic.id and epic.kind are required")
        }
        if (command.stories.isEmpty()) {
            throw ForgeCodedException("INVALID_FORGE_RUN_REQUEST", "stories must be a non-empty array")
        }
        val orchestratorRoot = (command.roots["orchestratorRoot"] as? String) ?: defaultOrchestratorRoot
        val resolved = ForgeRootsResolver.resolve(
            command.roots + mapOf(
                "orchestratorRoot" to orchestratorRoot,
                "runStoreRoot" to defaultRunStoreRoot(repoRoot),
                "runStorePolicy" to REPO_RUN_STORE_POLICY,
            ),
        )
        val result = ledgerStore.createEpicRun(
            CreateEpicRunRequest(
                roots = resolved,
                epic = command.epic,
                stories = command.stories,
                runId = command.runId,
            ),
        )
        return mapOf("runId" to result.runId, "filePath" to result.filePath)
    }

    /** List every valid Forge run projection in a run-store directory. */
    fun listProjections(runStoreRoot: String): List<Map<String, Any?>> = ledgerStore.listProjections(runStoreRoot)

    /** Read a projection from a ledger file path. */
    fun project(filePath: String): Map<String, Any?>? =
        ForgeLedger.projectForgeRun(ledgerStore.parse(filePath))

    /** Read the raw events of an Epic run ledger. */
    fun readEvents(roots: Map<String, Any?>, runId: String): List<Map<String, Any?>> {
        val resolved = io.whozoss.factory.forge.domain.ForgeRoots.fromMap(roots)
        val store = ForgeRootsResolver.ensureForgeRunStore(resolved.runStoreRoot)
        return ledgerStore.parse(Path.of(store, "$runId.jsonl").toString())
    }

    /**
     * Record a human G1 decision, enforcing idempotence and conflict detection.
     *
     * Actor identity and authority are injected by the trusted HTTP layer; they
     * are never read from the decision payload.
     */
    fun recordHumanDecision(
        roots: Map<String, Any?>,
        runId: String,
        decision: Map<String, Any?>,
        actorId: String?,
        authorityId: String?,
    ): Map<String, Any?> {
        val outcome = decision["outcome"]
        if (outcome !in ForgeHumanDecision.G1_OUTCOMES) {
            throw ForgeCodedException("G1_DECISION_INVALID", "decision.outcome must be approved or rejected")
        }
        if (decision["reasonCode"] !in ForgeHumanDecision.G1_REASON_CODES) {
            throw ForgeCodedException("G1_DECISION_INVALID", "decision.reasonCode is invalid")
        }
        if (decision.containsKey("actorId") || decision.containsKey("actorRole")) {
            throw ForgeCodedException(
                "G1_DECISION_INVALID",
                "actor identity and role must not be declared by the decision payload",
            )
        }
        val resolved = io.whozoss.factory.forge.domain.ForgeRoots.fromMap(roots)
        val store = ForgeRootsResolver.ensureForgeRunStore(resolved.runStoreRoot)
        val filePath = Path.of(store, "$runId.jsonl").toString()
        val events = ledgerStore.parse(filePath)
        val gate = events.filter { it["event"] == "gate_started" && it["runId"] == runId && it["gate"] == "G1" }
            .lastOrNull()
        if (gate == null || gate["status"] != "waiting_human") {
            throw ForgeCodedException("G1_NOT_WAITING", "G1 is not waiting for a human decision")
        }
        if (decision["gate"] != "G1" ||
            asInt(decision["attempt"]) != asInt(gate["attempt"]) ||
            decision["policyVersion"] != ForgeHumanDecision.G1_POLICY_VERSION
        ) {
            throw ForgeCodedException("G1_DECISION_INVALID", "decision does not match the active G1 attempt or policy")
        }
        val evidenceSetHash = ForgeHumanDecision.computeG1EvidenceSetHash(
            events,
            runId,
            asInt(gate["attempt"]),
            ForgeHumanDecision.G1_POLICY_VERSION,
        )
        if (decision["evidenceSetHash"] != evidenceSetHash) {
            throw ForgeCodedException("G1_DECISION_INVALID", "decision evidenceSetHash is stale or invalid")
        }
        if (actorId.isNullOrBlank()) throw ForgeCodedException("G1_DECISION_INVALID", "verified actor identity is required")
        if (authorityId.isNullOrBlank()) {
            throw ForgeCodedException("G1_DECISION_INVALID", "actor is not authorized to decide G1")
        }

        val existing = events.firstOrNull {
            it["event"] == "human_decision_recorded" && it["runId"] == runId &&
                it["gate"] == "G1" && asInt(it["attempt"]) == asInt(gate["attempt"])
        }
        val fingerprint = ForgeHumanDecision.canonicalG1(
            linkedMapOf(
                "outcome" to decision["outcome"],
                "reasonCode" to decision["reasonCode"],
                "evidenceSetHash" to evidenceSetHash,
                "actorId" to actorId,
                "authorityId" to authorityId,
            ),
        )
        if (existing != null) {
            if (existing["idempotencyKey"] == fingerprint) {
                return mapOf("status" to "idempotent", "event" to existing)
            }
            throw ForgeCodedException("G1_DECISION_CONFLICT", "a conflicting G1 decision already exists")
        }

        val event = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "event" to "human_decision_recorded",
            "decisionId" to "decision_${UUID.randomUUID()}",
            "runId" to runId,
            "gate" to "G1",
            "attempt" to gate["attempt"],
            "policyVersion" to ForgeHumanDecision.G1_POLICY_VERSION,
            "evidenceSetHash" to evidenceSetHash,
            "decision" to linkedMapOf(
                "actorId" to actorId,
                "authorityId" to authorityId,
                "outcome" to decision["outcome"],
                "reasonCode" to decision["reasonCode"],
            ),
            "idempotencyKey" to fingerprint,
            "at" to Instant.now().toString(),
        )
        ledgerStore.append(filePath, event)
        return mapOf("status" to "recorded", "event" to event)
    }
}
