package io.whozoss.factory.forge.domain

/**
 * Pure G1 human-decision domain: the gate policy version, the accepted
 * outcome/reason vocabulary, canonical serialization and the deterministic
 * evidence-set hash.
 *
 * Port of `factory/src/domain/forge-bmad/forge-human-decision.ts`.
 */
object ForgeHumanDecision {

    /** Policy version of the G1 human gate. */
    const val G1_POLICY_VERSION = "forge-g1-human-v1"

    /** Accepted G1 decision outcomes. */
    val G1_OUTCOMES: Set<String> = setOf("approved", "rejected")

    /** Accepted G1 decision reason codes. */
    val G1_REASON_CODES: Set<String> = setOf(
        "intent_confirmed",
        "intent_rejected",
        "scope_unclear",
        "risk_not_accepted",
    )

    /**
     * Canonical JSON serialization: object keys sorted recursively, arrays
     * preserved in order. Two payloads that differ only by key order serialize
     * identically.
     */
    fun canonicalG1(value: Any?): String = ForgeJson.stringify(ForgeJson.canonical(value))

    /** Deterministic hash of the G1 evidence set for a run attempt. */
    fun computeG1EvidenceSetHash(
        events: List<ForgeLedgerEvent>,
        runId: String,
        attempt: Int = 1,
        policyVersion: String = G1_POLICY_VERSION,
    ): String {
        val evidence = events.filter { event ->
            (event["event"] == "run_started" && event["runId"] == runId) ||
                (event["event"] == "story_run_created" && event["parentRunId"] == runId) ||
                (event["event"] == "gate_started" && event["runId"] == runId &&
                    event["gate"] == "G1" && asInt(event["attempt"]) == attempt)
        }
        return ForgeJson.sha256(canonicalG1(mapOf("policyVersion" to policyVersion, "evidence" to evidence)))
    }
}

/** Top-level aliases matching the Node export surface. */
const val G1_POLICY_VERSION = ForgeHumanDecision.G1_POLICY_VERSION

fun canonicalG1(value: Any?): String = ForgeHumanDecision.canonicalG1(value)

fun computeG1EvidenceSetHash(
    events: List<ForgeLedgerEvent>,
    runId: String,
    attempt: Int = 1,
    policyVersion: String = G1_POLICY_VERSION,
): String = ForgeHumanDecision.computeG1EvidenceSetHash(events, runId, attempt, policyVersion)
