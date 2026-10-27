package io.whozoss.factory.forge

import io.whozoss.factory.forge.domain.ForgeCodedException
import io.whozoss.factory.forge.domain.ForgeHumanDecision
import io.whozoss.factory.forge.domain.ForgeRoots
import io.whozoss.factory.forge.infrastructure.FileForgeLedgerStore
import io.whozoss.factory.forge.port.CreateEpicRunRequest
import io.whozoss.factory.forge.service.ForgeGateService
import io.whozoss.factory.forge.service.ForgeRunService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests of the G1 human decision and the deterministic G2 / G2-US
 * gates over a real file-backed ledger.
 */
class ForgeGatesIntegrationTest {

    private val ledgerStore = FileForgeLedgerStore()

    private val runService = ForgeRunService(ledgerStore)

    private val gateService = ForgeGateService(ledgerStore)

    private val epicSpec = """
        ---
        schemaVersion: 1
        workItem:
          id: EPIC-1
          kind: Epic
        scope:
          allow:
            - src/a.ts
            - src/b.ts
          create:
            - src/new.ts
          deny:
            - src/secret.ts
        oracles:
          - front.build
        ---
        # Epic
    """.trimIndent() + "\n"

    private val storySpecValid = """
        ---
        schemaVersion: 1
        workItem:
          id: STORY-1
          kind: Story
          parentId: EPIC-1
        scope:
          allow:
            - src/a.ts
          create:
            - src/new.ts
          deny:
            - src/secret.ts
        oracles:
          - front.build
        ---
        # Story
    """.trimIndent() + "\n"

    private val storySpecViolating = storySpecValid.replace("- src/a.ts", "- src/other.ts")

    private fun roots(root: Path) = ForgeRoots(
        orchestratorRoot = root.toString(),
        runStoreRoot = root.resolve("runs").toString(),
        repoRoot = root.toString(),
        runStorePolicy = "under_repo",
    )

    @Test
    fun `G1 decision is recorded, idempotent and conflict-detecting`() {
        val root = Files.createTempDirectory("forge-g1")
        val created = ledgers(root)
        val runId = created.first
        val events = ledgerStore.parse(created.second)
        val evidenceSetHash = ForgeHumanDecision.computeG1EvidenceSetHash(events, runId, 1)
        val rootsMap = roots(root).toMap()

        val decision = linkedMapOf<String, Any?>(
            "outcome" to "approved",
            "reasonCode" to "intent_confirmed",
            "gate" to "G1",
            "attempt" to 1,
            "policyVersion" to ForgeHumanDecision.G1_POLICY_VERSION,
            "evidenceSetHash" to evidenceSetHash,
        )

        val recorded = runService.recordHumanDecision(rootsMap, runId, decision, "actor-1", "authority-1")
        assertThat(recorded["status"]).isEqualTo("recorded")

        val idempotent = runService.recordHumanDecision(rootsMap, runId, decision, "actor-1", "authority-1")
        assertThat(idempotent["status"]).isEqualTo("idempotent")

        val conflicting = decision + mapOf("reasonCode" to "risk_not_accepted")
        assertThatThrownBy {
            runService.recordHumanDecision(rootsMap, runId, conflicting, "actor-1", "authority-1")
        }.isInstanceOf(ForgeCodedException::class.java)
    }

    @Test
    fun `G1 rejects a decision that declares its own identity`() {
        val root = Files.createTempDirectory("forge-g1-identity")
        val created = ledgers(root)
        val events = ledgerStore.parse(created.second)
        val evidenceSetHash = ForgeHumanDecision.computeG1EvidenceSetHash(events, created.first, 1)
        val decision = mapOf(
            "outcome" to "approved",
            "reasonCode" to "intent_confirmed",
            "gate" to "G1",
            "attempt" to 1,
            "policyVersion" to ForgeHumanDecision.G1_POLICY_VERSION,
            "evidenceSetHash" to evidenceSetHash,
            "actorId" to "spoofed",
        )
        assertThatThrownBy {
            runService.recordHumanDecision(mapOf("runStoreRoot" to roots(root).runStoreRoot), created.first, decision, "a", "b")
        }.isInstanceOf(ForgeCodedException::class.java)
    }

    @Test
    fun `G2 blocks until G1 is approved then passes and is idempotent`() {
        val root = Files.createTempDirectory("forge-g2")
        val specPath = root.resolve("epic-spec.md")
        Files.writeString(specPath, epicSpec)
        val created = ledgers(root)
        val runId = created.first
        val rootsMap = roots(root).toMap()

        val blocked = gateService.evaluateG2(rootsMap, runId, specPath.toString())
        assertThat(blocked["status"]).isEqualTo("recorded")
        assertThat((blocked["event"] as Map<*, *>)["code"]).isEqualTo("G2_G1_NOT_APPROVED")

        approveG1(rootsMap, runId, created.second)

        val passed = gateService.evaluateG2(rootsMap, runId, specPath.toString())
        assertThat(passed["status"]).isEqualTo("recorded")
        assertThat((passed["event"] as Map<*, *>)["status"]).isEqualTo("passed")

        val idempotent = gateService.evaluateG2(rootsMap, runId, specPath.toString())
        assertThat(idempotent["status"]).isEqualTo("idempotent")
    }

    @Test
    fun `G2-US blocks on an inheritance violation then passes on a valid story spec`() {
        val root = Files.createTempDirectory("forge-g2us")
        val epicSpecPath = root.resolve("epic-spec.md")
        val storySpecPath = root.resolve("story-spec.md")
        Files.writeString(epicSpecPath, epicSpec)
        Files.writeString(storySpecPath, storySpecViolating)

        val created = ledgers(root)
        val epicRunId = created.first
        val storyRunId = (created.third.first()["runId"] as String)
        val rootsMap = roots(root).toMap()

        approveG1(rootsMap, epicRunId, created.second)
        val g2 = gateService.evaluateG2(rootsMap, epicRunId, epicSpecPath.toString())
        assertThat((g2["event"] as Map<*, *>)["status"]).isEqualTo("passed")

        val blocked = gateService.evaluateG2US(rootsMap, epicRunId, storyRunId, storySpecPath.toString())
        assertThat((blocked["event"] as Map<*, *>)["code"]).isEqualTo("G2_US_INHERITANCE_VIOLATION")

        Files.writeString(storySpecPath, storySpecValid)
        val passed = gateService.evaluateG2US(rootsMap, epicRunId, storyRunId, storySpecPath.toString())
        assertThat((passed["event"] as Map<*, *>)["status"]).isEqualTo("passed")

        val idempotent = gateService.evaluateG2US(rootsMap, epicRunId, storyRunId, storySpecPath.toString())
        assertThat(idempotent["status"]).isEqualTo("idempotent")
    }

    private fun approveG1(rootsMap: Map<String, Any?>, runId: String, filePath: String) {
        val events = ledgerStore.parse(filePath)
        val evidence = ForgeHumanDecision.computeG1EvidenceSetHash(events, runId, 1)
        runService.recordHumanDecision(
            rootsMap,
            runId,
            mapOf(
                "outcome" to "approved",
                "reasonCode" to "intent_confirmed",
                "gate" to "G1",
                "attempt" to 1,
                "policyVersion" to ForgeHumanDecision.G1_POLICY_VERSION,
                "evidenceSetHash" to evidence,
            ),
            "actor-1",
            "authority-1",
        )
    }

    /** Create an Epic run with one Story; returns (runId, filePath, storyRuns). */
    private fun ledgers(root: Path): Triple<String, String, List<Map<String, Any?>>> {
        val result = ledgerStore.createEpicRun(
            CreateEpicRunRequest(
                roots = roots(root),
                epic = mapOf("id" to "EPIC-1", "kind" to "Epic"),
                stories = listOf(mapOf("id" to "STORY-1", "kind" to "Story")),
            ),
        )
        return Triple(result.runId, result.filePath, result.storyRuns)
    }
}
