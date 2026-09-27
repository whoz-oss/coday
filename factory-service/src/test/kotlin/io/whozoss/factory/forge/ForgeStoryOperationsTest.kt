package io.whozoss.factory.forge

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.forge.domain.ForgeCodedException
import io.whozoss.factory.forge.domain.ForgeSpec
import io.whozoss.factory.forge.domain.ForgeStoryOperations
import io.whozoss.factory.forge.domain.ForgeWorkflowAdapter
import io.whozoss.factory.forge.infrastructure.FileForgeLedgerStore
import io.whozoss.factory.forge.service.OracleCommandExecutor
import io.whozoss.factory.forge.service.OracleCommandResult
import io.whozoss.factory.forge.service.StoryOperationService
import io.whozoss.factory.forge.service.UnconfiguredStoryRuntime
import io.whozoss.factory.forge.service.UnconfiguredWorkspaceSnapshotter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests of the Story-phase helpers and the oracle campaign over a
 * real file-backed ledger.
 */
class ForgeStoryOperationsTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var ledgerStore: FileForgeLedgerStore

    // ----- pure plan parsing -------------------------------------------------

    @Test
    fun `uniquePlan enforces exactly one valid json plan`() {
        val valid = ForgeStoryOperations.uniquePlan("""prefix ```json
            {"files":["src/a.ts"],"doneWhen":"done"}
        ``` suffix""")
        assertThat(valid.ok).isTrue()
        assertThat(valid.plan!!.files).containsExactly("src/a.ts")

        assertThat(ForgeStoryOperations.uniquePlan("no block").code).isEqualTo("STORY_ANALYSIS_PLAN_JSON_MISSING")
        assertThat(
            ForgeStoryOperations.uniquePlan("```json\n{}\n```\n```json\n{}\n```").code,
        ).isEqualTo("STORY_ANALYSIS_PLAN_JSON_MULTIPLE")
        assertThat(
            ForgeStoryOperations.uniquePlan("```json\n{\"files\":[\"a\"],\"doneWhen\":\"d\",\"extra\":1}\n```").code,
        ).isEqualTo("STORY_ANALYSIS_PLAN_SCHEMA_EXTRA_KEY")
    }

    @Test
    fun `planFromArtifact rejects a missing plan`() {
        assertThatThrownBy { ForgeStoryOperations.planFromArtifact("no plan here") }
            .isInstanceOf(ForgeCodedException::class.java)
    }

    // ----- scope matching ----------------------------------------------------

    @Test
    fun `scope matching validates globs, denies and plan membership`() {
        val scope = mapOf(
            "allow" to listOf("src/*"),
            "create" to listOf("src/new/*"),
            "deny" to listOf("src/secret/*"),
        )
        assertThat(ForgeStoryOperations.scopeValid("src/a.ts", scope)).isTrue()
        assertThat(ForgeStoryOperations.scopeValid("src/nested/a.ts", scope)).isFalse()
        assertThat(ForgeStoryOperations.scopeValid("src/secret/x.ts", scope)).isFalse()

        val plan = io.whozoss.factory.forge.domain.ForgePlan(listOf("src/a.ts"), "done", null)
        assertThat(ForgeStoryOperations.allowedModified("src/a.ts", scope, plan)).isTrue()
        assertThat(ForgeStoryOperations.allowedModified("src/b.ts", scope, plan)).isFalse()
        assertThat(ForgeStoryOperations.allowedCreated("src/new/b.ts", scope)).isTrue()
        assertThat(ForgeStoryOperations.allowedCreated("src/new/secret/b.ts", scope)).isFalse()
    }

    // ----- workflow adapter --------------------------------------------------

    @Test
    fun `the workflow adapter adapts a complete run and rejects invalid ones`() {
        val gates = (1..4).associate { index ->
            "gate_$index" to mapOf(
                "startedAt" to "2026-01-0${index}T00:00:00Z",
                "decidedAt" to "2026-01-0${index + 1}T00:00:00Z",
                "humanDecision" to "approved",
            )
        }
        val adapted = ForgeWorkflowAdapter.adapt(
            mapOf(
                "ticketId" to "ABC-1",
                "ticketSummary" to "Fix",
                "gates" to gates,
                "runOutcome" to mapOf("status" to "completed"),
            ),
        )
        assertThat(adapted["ok"]).isEqualTo(true)
        val projection = adapted["projection"] as Map<*, *>
        assertThat(projection["workflowId"]).isEqualTo("forge-run-ABC-1")
        assertThat(projection["status"]).isEqualTo("completed")
        assertThat(projection["steps"] as List<*>).hasSize(4)

        val invalid = ForgeWorkflowAdapter.adapt(mapOf("ticketId" to "nope"))
        assertThat((invalid["error"] as Map<*, *>)["code"]).isEqualTo("INVALID_FORGE_RUN")

        val impossible = ForgeWorkflowAdapter.adapt(
            mapOf(
                "ticketId" to "ABC-2",
                "gates" to mapOf("gate_2" to mapOf("startedAt" to "2026-01-01T00:00:00Z")),
                "runOutcome" to mapOf("status" to "in-progress"),
            ),
        )
        assertThat((impossible["error"] as Map<*, *>)["code"]).isEqualTo("IMPOSSIBLE_FORGE_GATE_ORDER")

        val unknown = ForgeWorkflowAdapter.adapt(
            mapOf(
                "ticketId" to "ABC-3",
                "gates" to mapOf(
                    "gate_1" to mapOf(
                        "startedAt" to "2026-01-01T00:00:00Z",
                        "decidedAt" to "2026-01-02T00:00:00Z",
                        "humanDecision" to "maybe",
                    ),
                ),
                "runOutcome" to mapOf("status" to "in-progress"),
            ),
        )
        assertThat((unknown["error"] as Map<*, *>)["code"]).isEqualTo("UNKNOWN_FORGE_DECISION")
    }

    // ----- oracle campaign ---------------------------------------------------

    @Test
    fun `the oracle campaign records and projects a passing campaign`() {
        val root = Files.createTempDirectory("forge-oracles")
        val runStoreRoot = root.resolve("runs")
        Files.createDirectories(runStoreRoot)
        val specContent = """
            ---
            schemaVersion: 1
            workItem:
              id: EPIC-1
              kind: Epic
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
            # Epic
        """.trimIndent() + "\n"
        val specPath = root.resolve("spec.md")
        Files.writeString(specPath, specContent)
        val specHash = ForgeSpec.computeForgeSpecHash(specContent)

        val file = runStoreRoot.resolve("epic_1.jsonl")
        val events = listOf(
            """{"schemaVersion":1,"event":"run_started","runId":"epic_1","runType":"EpicRun","workItem":{"id":"EPIC-1","kind":"Epic"},"roots":{"repoRoot":"$root","runStoreRoot":"$runStoreRoot"},"at":"2026-01-01T00:00:00.000Z"}""",
            """{"schemaVersion":1,"event":"story_run_created","runId":"story_1","parentRunId":"epic_1","runType":"StoryRun","ordinal":1,"workItem":{"id":"STORY-1","kind":"Story"}}""",
            """{"schemaVersion":1,"event":"gate_started","runId":"epic_1","gate":"G1","attempt":1,"status":"waiting_human"}""",
            """{"schemaVersion":1,"event":"human_decision_recorded","runId":"epic_1","gate":"G1","attempt":1,"decision":{"outcome":"approved","reasonCode":"intent_confirmed"}}""",
            """{"schemaVersion":1,"event":"g2_evaluated","runId":"epic_1","gate":"G2","attempt":1,"status":"passed","code":"G2_SPEC_VALID","policyVersion":"forge-g2-deterministic-v1","spec":{"path":"$specPath","sha256":"$specHash"}}""",
            """{"schemaVersion":1,"event":"story_edit_finished","runId":"epic_1","storyRunId":"story_1","editId":"edit_1","status":"finished","outcome":"finished","diffValidation":{"status":"valid"},"filesModified":["src/a.ts"],"filesCreated":[]}""",
        ).joinToString("\n")
        Files.writeString(file, events)

        val executor = OracleCommandExecutor { _, _, _ -> OracleCommandResult(exitCode = 0, durationMs = 7) }
        val service = StoryOperationService(
            ledgerStore = ledgerStore,
            runtime = UnconfiguredStoryRuntime(),
            snapshots = UnconfiguredWorkspaceSnapshotter(),
            executor = executor,
        )
        val frontResolver: (io.whozoss.factory.forge.domain.FrontOraclePlanInput) -> Map<String, Any?> = { _ ->
            mapOf(
                "owners" to listOf("app"),
                "ownersWithTestTarget" to emptyList<String>(),
                "ownersWithoutTestTarget" to listOf("app"),
                "build" to mapOf("command" to "pnpm build", "buildHosts" to listOf("app")),
                "tests" to mapOf("command" to null, "owners" to emptyList<String>()),
            )
        }

        val result = service.executeStoryOracles(
            runStoreRoot = runStoreRoot.toString(),
            epicRunId = "epic_1",
            storyRunId = "story_1",
            editId = "edit_1",
            expectedSpecHash = specHash,
            attempt = 1,
            hostMapRaw = null,
            frontResolver = frontResolver,
        )

        assertThat(result["status"]).isEqualTo("passed")
        assertThat(result["results"] as List<*>).hasSize(1)

        val projection = io.whozoss.factory.forge.domain.projectForgeRun(ledgerStore.parse(file.toString()))!!
        val story = (projection["stories"] as List<*>).first() as Map<*, *>
        val campaigns = story["oracleCampaigns"] as List<*>
        assertThat(campaigns).hasSize(1)
        assertThat((campaigns[0] as Map<*, *>)["status"]).isEqualTo("passed")
    }

    @Test
    fun `the oracle campaign rejects an unvalidated edit`() {
        val root = Files.createTempDirectory("forge-oracles-invalid")
        val runStoreRoot = root.resolve("runs")
        Files.createDirectories(runStoreRoot)
        val file = runStoreRoot.resolve("epic_2.jsonl")
        Files.writeString(
            file,
            listOf(
                """{"schemaVersion":1,"event":"run_started","runId":"epic_2","runType":"EpicRun","workItem":{"id":"EPIC-1","kind":"Epic"},"roots":{"repoRoot":"$root","runStoreRoot":"$runStoreRoot"}}""",
                """{"schemaVersion":1,"event":"story_run_created","runId":"story_2","parentRunId":"epic_2","runType":"StoryRun","ordinal":1,"workItem":{"id":"STORY-1","kind":"Story"}}""",
            ).joinToString("\n"),
        )
        val service = StoryOperationService(
            ledgerStore = ledgerStore,
            runtime = UnconfiguredStoryRuntime(),
            snapshots = UnconfiguredWorkspaceSnapshotter(),
            executor = OracleCommandExecutor { _, _, _ -> OracleCommandResult(0, 0) },
        )
        assertThatThrownBy {
            service.executeStoryOracles(runStoreRoot.toString(), "epic_2", "story_2", "edit_x", "sha256:x")
        }.isInstanceOf(ForgeCodedException::class.java)
    }

    @Test
    fun `plan files inside the repo are checked by the plan gate`() {
        val root = Files.createTempDirectory("forge-plan")
        Files.createDirectories(Path.of(root.toString(), "src"))
        Files.writeString(Path.of(root.toString(), "src", "a.ts"), "export {}")
        val result = io.whozoss.factory.forge.domain.ForgePlanParser.checkPlanFiles(listOf("src/a.ts", "src/missing.ts"), root.toString())
        assertThat(result["missingFiles"] as List<*>).containsExactly("src/missing.ts")
    }
}
