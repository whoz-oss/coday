package io.whozoss.factory.forge.bmad

import io.whozoss.factory.sdk.spi.HumanCheckpointContext
import io.whozoss.factory.sdk.spi.HumanCheckpointVerdict
import io.whozoss.factory.sdk.spi.RunView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForgePacketHumanCheckpointPolicyTest {
    private val policy = ForgeDeliveryHumanCheckpointPolicy()
    private val packet = mapOf(
        "repository" to "whoz",
        "commit" to "a".repeat(40),
        "manifestSha256" to "b".repeat(64),
    )

    private fun context(state: Map<String, Any?> = mapOf("approvedPacketCandidate" to packet),
                        step: String = "packet-approval", decisions: List<Map<String, Any?>> = emptyList()) =
        HumanCheckpointContext(RunView("run-1", "forge.delivery", domainState = state, humanDecisions = decisions),
            "forge.delivery", step)

    private val candidateState: Map<String, Any?> get() = mapOf(
        "approvedPacketCandidate" to packet,
        "build_report" to mapOf("manifest_sha256" to packet["manifestSha256"], "work_commit" to "c".repeat(40)),
        "verification_report" to mapOf("work_commit" to "c".repeat(40)),
        "review_report" to mapOf("work_commit" to "c".repeat(40), "status" to "PASS"),
    )

    @Test
    fun `packet decision carries the exact presented Git identity`() {
        assertThat(policy.supports("forge.delivery", "packet-approval")).isTrue()
        val presented = policy.present(context()) as HumanCheckpointVerdict.Allowed
        assertThat(presented.facts).isEqualTo(packet)
        val accepted = policy.decide(context(), presented.facts, "approve") as HumanCheckpointVerdict.Allowed
        assertThat(accepted.facts).containsAllEntriesOf(packet).containsEntry("gate", "packet-approval")
    }

    @Test
    fun `packet presentation names every document in the manifest`() {
        val entries = (1..16).joinToString(",") { index ->
            """{"kind":"other_plan","path":"docs/$index.md","locator":"git:docs/$index.md"}"""
        }
        val withManifest = packet + ("canonicalManifest" to """{"entries":[$entries]}""")
        val presented = policy.present(context(mapOf("approvedPacketCandidate" to withManifest)))
            as HumanCheckpointVerdict.Allowed
        assertThat(presented.prompt).contains("git:docs/1.md", "git:docs/16.md")
    }

    @Test
    fun `changed or missing packet refuses the human reply`() {
        val presented = (policy.present(context()) as HumanCheckpointVerdict.Allowed).facts
        assertThat(policy.decide(context(mapOf("approvedPacketCandidate" to packet +
            ("commit" to "c".repeat(40)))), presented, "approve"))
            .isEqualTo(HumanCheckpointVerdict.Denied("FORGE_DECISION_STALE"))
        assertThat(policy.decide(context(emptyMap()), presented, "approve"))
            .isEqualTo(HumanCheckpointVerdict.Denied("PACKET_IDENTITY_MISSING"))
    }

    @Test
    fun `technical and functional decisions share one work candidate without automatic deployment proof`() {
        val technical = policy.present(context(candidateState, "technical-validation")) as HumanCheckpointVerdict.Allowed
        assertThat(technical.facts).containsEntry("workCommit", "c".repeat(40))
        val functional = policy.present(context(candidateState, "functional-validation")) as HumanCheckpointVerdict.Allowed
        assertThat(functional.facts).containsEntry("workCommit", "c".repeat(40))
        assertThat(functional.prompt).contains("Factory ne vérifie pas le déploiement automatiquement")
        assertThat(technical.facts["candidateId"]).isEqualTo(functional.facts["candidateId"])
        assertThat(policy.decide(context(candidateState, "functional-validation"), functional.facts, "approve"))
            .isInstanceOf(HumanCheckpointVerdict.Allowed::class.java)
        val changedWork = candidateState + ("build_report" to mapOf(
            "manifest_sha256" to packet["manifestSha256"], "work_commit" to "d".repeat(40)))
        assertThat(policy.decide(context(changedWork, "functional-validation"), functional.facts, "approve"))
            .isEqualTo(HumanCheckpointVerdict.Denied("FUNCTIONAL_CANDIDATE_MISSING"))
    }

    @Test
    fun `second human approval marks ready to merge only when the first audited approval matches`() {
        val technical = policy.present(context(candidateState, "technical-validation")) as HumanCheckpointVerdict.Allowed
        val functional = policy.present(context(candidateState, "functional-validation")) as HumanCheckpointVerdict.Allowed
        val first = mapOf<String, Any?>(
            "stepId" to "technical-validation", "outcome" to "pass",
            "source" to mapOf("kind" to "factory-human", "actorId" to "member-1"),
            "facts" to technical.facts + mapOf("gate" to "technical-validation", "decidedAt" to "2026-11-19T00:00:00Z"),
        )
        val pending = policy.decide(context(candidateState, "functional-validation"), functional.facts, "approve")
            as HumanCheckpointVerdict.Allowed
        assertThat(pending.facts).doesNotContainKey("deliveryOutcome")
        val ready = policy.decide(context(candidateState, "functional-validation", listOf(first)), functional.facts, "approve")
            as HumanCheckpointVerdict.Allowed
        assertThat(ready.facts).containsEntry("deliveryOutcome", "READY_TO_MERGE")
        val stale = first + ("facts" to mapOf("gate" to "technical-validation", "decidedAt" to "2026-11-19T00:00:00Z"))
        assertThat(policy.decide(context(candidateState, "functional-validation", listOf(stale)), functional.facts, "approve"))
            .isEqualTo(HumanCheckpointVerdict.Denied("FORGE_DECISION_STALE"))
    }
}
