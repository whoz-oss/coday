package io.whozoss.factory.forge

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.forge.domain.ForgeRoots
import io.whozoss.factory.forge.port.CreateEpicRunRequest
import io.whozoss.factory.forge.service.ForgeRunService
import io.whozoss.factory.forge.infrastructure.FileForgeLedgerStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests of the file-backed Forge ledger: append-only JSONL writes,
 * parsing validation and the deterministic `projectForgeRun` replay.
 */
class ForgeLedgerIntegrationTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var ledgerStore: FileForgeLedgerStore

    @Autowired
    private lateinit var runService: ForgeRunService

    private fun roots(root: Path) = ForgeRoots(
        orchestratorRoot = root.toString(),
        runStoreRoot = root.resolve("runs").toString(),
        repoRoot = root.toString(),
        runStorePolicy = "under_repo",
    )

    @Test
    fun `parseForgeLedgerLines accepts a valid append-only journal`() {
        val raw = """
            {"schemaVersion":1,"event":"run_started","runId":"epic_1","runType":"EpicRun","at":"2026-01-01T00:00:00.000Z"}
            {"schemaVersion":1,"event":"gate_started","runId":"epic_1","gate":"G1","attempt":1,"status":"waiting_human"}
        """.trimIndent()

        val events = io.whozoss.factory.forge.domain.parseForgeLedgerLines(raw)

        assertThat(events).hasSize(2)
        assertThat(events[0]["runId"]).isEqualTo("epic_1")
        assertThat(events[1]["gate"]).isEqualTo("G1")
    }

    @Test
    fun `parseForgeLedgerLines rejects malformed JSON and unsupported schema`() {
        assertThatThrownBy { io.whozoss.factory.forge.domain.parseForgeLedgerLines("{not json}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid JSONL at line 1")

        assertThatThrownBy {
            io.whozoss.factory.forge.domain.parseForgeLedgerLines("""{"schemaVersion":99,"event":"run_started"}""")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupported forge ledger schema")
    }

    @Test
    fun `createEpicRun writes the opening events and is append-only`() {
        val root = Files.createTempDirectory("forge-ledger")
        val result = ledgerStore.createEpicRun(
            CreateEpicRunRequest(
                roots = roots(root),
                epic = mapOf("id" to "EPIC-1", "kind" to "Epic"),
                stories = listOf(
                    mapOf("id" to "STORY-1", "kind" to "Story"),
                    mapOf("id" to "STORY-2", "kind" to "Story"),
                ),
            ),
        )

        val events = ledgerStore.parse(result.filePath)
        assertThat(events.map { it["event"] }).containsExactly(
            "run_started",
            "story_run_created",
            "story_run_created",
            "gate_started",
        )
        assertThat(events[0]["workflow"]).isEqualTo("forge-epic-v1")
        assertThat(result.storyRuns).hasSize(2)

        val beforeCount = events.size
        ledgerStore.append(result.filePath, mapOf("schemaVersion" to 1, "event" to "custom", "runId" to result.runId))
        val after = ledgerStore.parse(result.filePath)
        assertThat(after).hasSize(beforeCount + 1)
        // The original lines are byte-preserved (append-only semantics).
        val content = Files.readString(Path.of(result.filePath))
        assertThat(content.lines().filter { it.isNotEmpty() }).hasSize(beforeCount + 1)
    }

    @Test
    fun `projectForgeRun derives the display state from the journal`() {
        val raw = listOf(
            """{"schemaVersion":1,"event":"run_started","runId":"epic_1","runType":"EpicRun","workflow":"forge-epic-v1","workItem":{"id":"EPIC-1","kind":"Epic"},"roots":{"repoRoot":"/r","runStoreRoot":"/r/runs"},"at":"2026-01-01T00:00:00.000Z"}""",
            """{"schemaVersion":1,"event":"story_run_created","runId":"story_1","parentRunId":"epic_1","runType":"StoryRun","ordinal":1,"workItem":{"id":"STORY-1","kind":"Story"}}""",
            """{"schemaVersion":1,"event":"gate_started","runId":"epic_1","gate":"G1","attempt":1,"status":"waiting_human","policyVersion":"forge-g1-human-v1"}""",
            """{"schemaVersion":1,"event":"human_decision_recorded","runId":"epic_1","gate":"G1","attempt":1,"decision":{"outcome":"approved","reasonCode":"intent_confirmed"}}""",
            """{"schemaVersion":1,"event":"g2_evaluated","runId":"epic_1","gate":"G2","attempt":1,"status":"passed","code":"G2_SPEC_VALID","policyVersion":"forge-g2-deterministic-v1","spec":{"path":"/r/spec.md","sha256":"sha256:x"}}""",
            """{"schemaVersion":1,"event":"story_edit_finished","runId":"epic_1","storyRunId":"story_1","editId":"edit_1","status":"finished","outcome":"finished","caseId":"c1","diffValidation":{"status":"valid"},"filesModified":["src/a.ts"],"filesCreated":[]}""",
            """{"schemaVersion":1,"event":"story_oracles_started","campaignId":"oracle_1","runId":"epic_1","storyRunId":"story_1","editId":"edit_1","attempt":1}""",
            """{"schemaVersion":1,"event":"story_oracle_finished","campaignId":"oracle_1","name":"front.build","status":"passed","code":"ORACLE_PASS","ownerProjects":["app"],"target":"build","buildHosts":["app"],"exitCode":0,"durationMs":12,"commandHash":"sha256:c"}""",
            """{"schemaVersion":1,"event":"story_g3_evaluated","campaignId":"oracle_1","runId":"epic_1","storyRunId":"story_1","editId":"edit_1","attempt":1,"status":"passed","specHash":"sha256:x","policyVersion":"forge-story-oracles-v1"}""",
            """{"schemaVersion":1,"event":"g2_us_evaluated","runId":"epic_1","storyRunId":"story_1","gate":"G2-US","attempt":1,"status":"passed","code":"G2_US_SPEC_VALID","storySpec":{"sha256":"sha256:y"}}""",
        ).joinToString("\n")

        val projection = io.whozoss.factory.forge.domain.projectForgeRun(
            io.whozoss.factory.forge.domain.parseForgeLedgerLines(raw),
        )!!

        assertThat(projection["runId"]).isEqualTo("epic_1")
        assertThat(projection["status"]).isEqualTo("approved")
        val gates = projection["gates"] as List<*>
        assertThat(gates).hasSize(2)
        val g1 = gates[0] as Map<*, *>
        assertThat(g1["gate"]).isEqualTo("G1")
        assertThat(g1["evidenceSetHash"]).asString().startsWith("sha256:")

        val stories = projection["stories"] as List<*>
        val story = stories[0] as Map<*, *>
        assertThat(story["runId"]).isEqualTo("story_1")
        assertThat(story["status"]).isEqualTo("passed")
        assertThat(story["edits"] as List<*>).hasSize(1)
        assertThat(story["oracleCampaigns"] as List<*>).hasSize(1)
        assertThat((story["storyG2"] as Map<*, *>)["gate"]).isEqualTo("G2-US")
    }

    @Test
    fun `listForgeRunProjections returns valid runs and skips invalid ledgers`() {
        val root = Files.createTempDirectory("forge-list")
        val runStoreRoot = roots(root).runStoreRoot
        ledgerStore.createEpicRun(
            CreateEpicRunRequest(
                roots = roots(root),
                epic = mapOf("id" to "EPIC-A", "kind" to "Epic"),
                stories = listOf(mapOf("id" to "STORY-A", "kind" to "Story")),
            ),
        )
        // A legacy/invalid file must not break the listing.
        Files.createDirectories(Path.of(runStoreRoot))
        Files.writeString(Path.of(runStoreRoot, "legacy.jsonl"), """{"kind":"run_start"}""")

        val projections = runService.listProjections(runStoreRoot)
        assertThat(projections).hasSize(1)
        assertThat(projections[0]["runId"]).asString().startsWith("epic_")
    }
}
