package io.whozoss.factory.forge.service

import io.whozoss.factory.forge.domain.ForgeCodedException
import io.whozoss.factory.forge.domain.ForgeFrontOracleResolution
import io.whozoss.factory.forge.domain.ForgePlan
import io.whozoss.factory.forge.domain.ForgeStoryOperations
import io.whozoss.factory.forge.domain.FrontOraclePlanInput
import io.whozoss.factory.forge.domain.asMap
import io.whozoss.factory.forge.port.ForgeLedgerStore
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** Default front build template (port of `domains.front.oracles[build].command`). */
private const val FRONT_BUILD_TEMPLATE =
    "pnpm nx run-many --target=build --configuration=development --skip-nx-cache"

/** Result of a shell command launched by the oracle campaign. */
data class OracleCommandResult(
    val exitCode: Int,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val crashed: Boolean = false,
)

/** The AgentOS + workspace surface used by the Story phases. */
interface StoryRuntime {
    fun preflightAgent(namespaceId: String, agentName: String): Map<String, Any?>
    fun preflightReadOnlyWorkspace(namespaceId: String, agent: Any?, repoRoot: String): Map<String, Any?>
    fun preflightWritableWorkspace(namespaceId: String, agent: Any?, repoRoot: String): Map<String, Any?>
    fun createCase(namespaceId: String, title: String): Map<String, Any?>
    fun runAgentTurn(caseId: String, agentName: String, brief: String): Map<String, Any?>
}

/** Git snapshot/diff used by the Story edit phase. */
interface WorkspaceSnapshotter {
    fun snapshot(repoRoot: String): Any?
    fun diffSince(before: Any?, repoRoot: String): Map<String, List<String>>
}

/** Command executor used by the Story oracle campaign. */
fun interface OracleCommandExecutor {
    fun run(command: String, cwd: String, timeoutMs: Long): OracleCommandResult
}

/**
 * Default runtime bound by Spring when no AgentOS runtime is configured.
 *
 * The Story phases are only reachable through trusted Factory control-plane
 * routes; with no AgentOS executor wired, they fail with an explicit code.
 */
@Component
class UnconfiguredStoryRuntime : StoryRuntime {
    private fun unavailable(): Nothing =
        throw ForgeCodedException("STORY_RUNTIME_UNAVAILABLE", "No AgentOS Story runtime is configured.")

    override fun preflightAgent(namespaceId: String, agentName: String): Map<String, Any?> = unavailable()

    override fun preflightReadOnlyWorkspace(namespaceId: String, agent: Any?, repoRoot: String): Map<String, Any?> =
        unavailable()

    override fun preflightWritableWorkspace(namespaceId: String, agent: Any?, repoRoot: String): Map<String, Any?> =
        unavailable()

    override fun createCase(namespaceId: String, title: String): Map<String, Any?> = unavailable()

    override fun runAgentTurn(caseId: String, agentName: String, brief: String): Map<String, Any?> = unavailable()
}

/** Default workspace snapshotter: no-op (no git control plane wired). */
@Component
class UnconfiguredWorkspaceSnapshotter : WorkspaceSnapshotter {
    override fun snapshot(repoRoot: String): Any? = null
    override fun diffSince(before: Any?, repoRoot: String): Map<String, List<String>> =
        mapOf("modified" to emptyList(), "untracked" to emptyList())
}

/** Default oracle command executor: reports the command as crashed/blocked. */
@Component
class UnconfiguredOracleCommandExecutor : OracleCommandExecutor {
    override fun run(command: String, cwd: String, timeoutMs: Long): OracleCommandResult =
        OracleCommandResult(exitCode = -1, durationMs = 0, crashed = true)
}

/**
 * Application service for the Story analysis, edit and oracle phases.
 *
 * Port of `factory/src/application/forge-bmad/forge-story-analysis.ts`,
 * `forge-story-edit.ts` and `forge-story-oracles.ts`. All external effects
 * (AgentOS, git, command execution) are injected, keeping the ledger and
 * validation logic deterministic and testable.
 */
@Service
class StoryOperationService(
    private val ledgerStore: ForgeLedgerStore,
    private val runtime: StoryRuntime,
    private val snapshots: WorkspaceSnapshotter,
    private val executor: OracleCommandExecutor,
) {

    private fun store(runStoreRoot: String): String =
        io.whozoss.factory.forge.infrastructure.ForgeRootsResolver.ensureForgeRunStore(runStoreRoot)

    private fun filePath(runStoreRoot: String, epicRunId: String): String =
        Path.of(store(runStoreRoot), "$epicRunId.jsonl").toString()

    /** Write a Story analysis artifact atomically under the run store. */
    fun writeStoryAnalysisArtifact(store: String, runId: String, executionId: String, content: String): Map<String, Any?> {
        if (!Regex("^[A-Za-z0-9_-]+$").matches(runId) || !Regex("^exec_[A-Za-z0-9_-]+$").matches(executionId)) {
            throw ForgeCodedException("STORY_ANALYSIS_ARTIFACT_ID_INVALID")
        }
        val root = Path.of(store).toAbsolutePath().normalize()
        val dir = root.resolve("artifacts").resolve(runId)
        val finalPath = dir.resolve("$executionId.md")
        if (!dir.startsWith(root) || !finalPath.startsWith(dir)) {
            throw ForgeCodedException("STORY_ANALYSIS_ARTIFACT_PATH_INVALID")
        }
        Files.createDirectories(dir)
        val temporary = dir.resolve(".$executionId.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, content)
        Files.move(temporary, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return linkedMapOf(
            "kind" to "agent-analysis-output",
            "path" to root.relativize(finalPath).toString(),
            "sha256" to io.whozoss.factory.forge.domain.ForgeSpec.computeForgeSpecHash(content),
            "mediaType" to "text/markdown",
            "schemaVersion" to 1,
        )
    }

    // -----------------------------------------------------------------------
    // Story analysis
    // -----------------------------------------------------------------------

    fun executeStoryAnalysis(
        runStoreRoot: String,
        epicRunId: String,
        storyRunId: String,
        namespaceId: String,
        agentName: String,
        expectedSpecHash: String?,
        storySpecHash: String?,
        supplement: String?,
    ): Map<String, Any?> {
        if (namespaceId.isBlank() || agentName.isBlank()) throw ForgeCodedException("STORY_ANALYSIS_INPUT_INVALID")
        if (supplement != null && supplement.length > ForgeStoryOperations.MAX_TEXT) {
            throw ForgeCodedException("STORY_ANALYSIS_SUPPLEMENT_INVALID")
        }
        val store = store(runStoreRoot)
        val path = Path.of(store, "$epicRunId.jsonl").toString()
        val events = ledgerStore.parse(path)
        val epic = events.firstOrNull { it["event"] == "run_started" && it["runId"] == epicRunId }
        val story = events.firstOrNull {
            it["event"] == "story_run_created" && it["runId"] == storyRunId && it["parentRunId"] == epicRunId
        }
        if (epic == null || story == null) throw ForgeCodedException("STORY_RUN_NOT_FOUND")
        if (g1Outcome(events, epicRunId) != "approved") throw ForgeCodedException("STORY_ANALYSIS_G1_NOT_APPROVED")
        val g2Event = events.filter {
            it["event"] == "g2_evaluated" && it["runId"] == epicRunId && it["status"] == "passed"
        }.lastOrNull()?.takeIf { expectedSpecHash == null || asMap(it["spec"])?.get("sha256") == expectedSpecHash }
            ?: throw ForgeCodedException("STORY_ANALYSIS_G2_NOT_PASSED")
        if (storySpecHash != null && events.none {
                it["event"] == "g2_us_evaluated" && it["storyRunId"] == storyRunId && it["status"] == "passed" &&
                    asMap(it["storySpec"])?.get("sha256") == storySpecHash
            }
        ) {
            throw ForgeCodedException("STORY_ANALYSIS_G2_US_NOT_PASSED")
        }
        val specPath = asMap(g2Event["spec"])?.get("path") as? String
            ?: throw ForgeCodedException("STORY_ANALYSIS_SPEC_HASH_STALE")
        val spec = io.whozoss.factory.forge.infrastructure.ForgeSpecReader.loadForgeSpec(
            specPath,
            rootsRepo(events, epicRunId),
            rootsForge(events, epicRunId),
            workItem(epic["workItem"]),
        )
        if (spec.sha256 != asMap(g2Event["spec"])?.get("sha256")) {
            throw ForgeCodedException("STORY_ANALYSIS_SPEC_HASH_STALE")
        }
        val agent = runtime.preflightAgent(namespaceId, agentName)
        if (agent["ok"] != true) throw ForgeCodedException("STORY_ANALYSIS_AGENT_PREFLIGHT_FAILED:${agent["reason"]}")
        val ro = runtime.preflightReadOnlyWorkspace(namespaceId, agent["agent"], rootsRepo(events, epicRunId))
        if (ro["ok"] != true) throw ForgeCodedException("STORY_ANALYSIS_READ_ONLY_PREFLIGHT_FAILED:${ro["reason"]}")

        val executionId = "exec_${UUID.randomUUID()}"
        val created = runtime.createCase(namespaceId, "Forge analysis ${asMap(story["workItem"])?.get("id")}")
        val caseId = created["id"] as? String ?: ""
        val brief = ForgeStoryOperations.buildContextEnvelope(
            epic = epic,
            story = story,
            specPath = spec.path,
            specHash = spec.sha256,
            policyVersion = g2Event["policyVersion"] as? String ?: "",
            frontmatter = spec.frontmatter,
            supplement = supplement,
        ).brief
        ledgerStore.append(
            path,
            linkedMapOf(
                "schemaVersion" to 1,
                "event" to "agent_execution_started",
                "runId" to epicRunId,
                "parentRunId" to epicRunId,
                "executionId" to executionId,
                "caseId" to caseId,
                "storyRunId" to storyRunId,
                "role" to "analyst",
                "agentName" to agentName,
                "namespaceId" to namespaceId,
                "observedAt" to Instant.now().toString(),
                "status" to "started",
                "policyVersion" to ForgeStoryOperations.STORY_ANALYSIS_POLICY_VERSION,
                "briefArtifact" to mapOf(
                    "kind" to "brief",
                    "sha256" to io.whozoss.factory.forge.domain.ForgeSpec.computeForgeSpecHash(brief),
                    "mediaType" to "text/plain",
                    "schemaVersion" to 1,
                ),
            ),
        )
        val turn = try {
            runtime.runAgentTurn(caseId, agentName, brief)
        } catch (_: Exception) {
            mapOf("status" to "error", "caseStatus" to null, "killedByBudget" to false, "message" to "")
        }
        val status = if (turn["status"] == "finished") "finished" else "failed"
        val base = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "event" to "agent_execution_finished",
            "runId" to epicRunId,
            "parentRunId" to epicRunId,
            "executionId" to executionId,
            "caseId" to caseId,
            "storyRunId" to storyRunId,
            "role" to "analyst",
            "agentName" to agentName,
            "namespaceId" to namespaceId,
            "observedAt" to Instant.now().toString(),
            "status" to status,
            "policyVersion" to ForgeStoryOperations.STORY_ANALYSIS_POLICY_VERSION,
            "outcome" to turn["status"],
            "caseStatus" to turn["caseStatus"],
            "killedByBudget" to (turn["killedByBudget"] == true),
        )
        if (turn["status"] != "finished") {
            ledgerStore.append(path, base)
            return mapOf("execution" to base, "outcome" to turn["status"])
        }
        val output = turn["message"] as? String ?: ""
        if (output.isEmpty()) {
            val validation = mapOf(
                "status" to "invalid",
                "code" to "STORY_ANALYSIS_OUTPUT_MISSING",
                "schemaVersion" to ForgeStoryOperations.STORY_ANALYSIS_PLAN_SCHEMA_VERSION,
                "message" to "Agent runtime finished without a persisted analysis message.",
            )
            ledgerStore.append(path, base + mapOf("analysisValidation" to validation))
            return mapOf("execution" to base, "outcome" to turn["status"], "validation" to validation)
        }
        val descriptor = writeStoryAnalysisArtifact(store, epicRunId, executionId, output)
        ledgerStore.append(path, base + mapOf("artifact" to descriptor))
        val parsed = ForgeStoryOperations.uniquePlan(output)
        val validation: Map<String, Any?> = when {
            !parsed.ok -> mapOf("status" to "invalid", "code" to parsed.code)
            parsed.plan!!.files.size > ForgeStoryOperations.MAX_FILES -> mapOf("status" to "invalid", "code" to "STORY_ANALYSIS_PLAN_LIMIT")
            else -> {
                val scope = asMap(spec.frontmatter["scope"]) ?: emptyMap()
                val files = parsed.plan.files
                val missing = files.filter { !Files.exists(Path.of(rootsRepo(events, epicRunId), it)) }
                val outside = files.filter { !ForgeStoryOperations.scopeValid(it, scope) }
                when {
                    missing.isNotEmpty() -> mapOf("status" to "invalid", "code" to "STORY_ANALYSIS_PLAN_FILE_MISSING", "missingFiles" to missing)
                    outside.isNotEmpty() -> mapOf("status" to "invalid", "code" to "STORY_ANALYSIS_PLAN_OUT_OF_SCOPE", "outsideFiles" to outside)
                    else -> mapOf("status" to "valid", "fileCount" to files.size)
                }
            }
        }
        ledgerStore.append(
            path,
            linkedMapOf(
                "schemaVersion" to 1,
                "event" to "story_analysis_plan_validated",
                "runId" to epicRunId,
                "storyRunId" to storyRunId,
                "executionId" to executionId,
                "planSchemaVersion" to ForgeStoryOperations.STORY_ANALYSIS_PLAN_SCHEMA_VERSION,
                "status" to validation["status"],
                "code" to (validation["code"] ?: "STORY_ANALYSIS_PLAN_VALID"),
                "missingFiles" to validation["missingFiles"],
                "outsideFiles" to validation["outsideFiles"],
                "artifact" to descriptor,
                "at" to Instant.now().toString(),
            ),
        )
        return mapOf("execution" to base, "outcome" to turn["status"], "validation" to validation)
    }

    // -----------------------------------------------------------------------
    // Story edit
    // -----------------------------------------------------------------------

    fun executeStoryEdit(
        runStoreRoot: String,
        epicRunId: String,
        storyRunId: String,
        analysisExecutionId: String,
        namespaceId: String,
        agentName: String,
        expectedSpecHash: String?,
        storySpecHash: String?,
        supplement: String?,
    ): Map<String, Any?> {
        if (namespaceId.isBlank() || agentName.isBlank()) {
            throw ForgeCodedException("STORY_EDIT_INPUT_INVALID", "namespaceId and agentName are required.")
        }
        if (supplement != null && supplement.length > 4000) {
            throw ForgeCodedException("STORY_EDIT_SUPPLEMENT_INVALID")
        }
        val store = store(runStoreRoot)
        val filePath = Path.of(store, "$epicRunId.jsonl")
        if (!Files.exists(filePath)) throw ForgeCodedException("STORY_EDIT_RUN_NOT_FOUND")
        val events = ledgerStore.parse(filePath.toString())
        val epic = events.firstOrNull { it["event"] == "run_started" && it["runId"] == epicRunId }
            ?: throw ForgeCodedException("STORY_EDIT_RUN_NOT_FOUND")
        val story = events.firstOrNull {
            it["event"] == "story_run_created" && it["runId"] == storyRunId && it["parentRunId"] == epicRunId
        } ?: throw ForgeCodedException("STORY_EDIT_STORY_NOT_FOUND")
        if (g1Outcome(events, epicRunId) != "approved") throw ForgeCodedException("STORY_EDIT_G1_NOT_APPROVED")
        val g2 = events.filter {
            it["event"] == "g2_evaluated" && it["runId"] == epicRunId && it["status"] == "passed"
        }.lastOrNull() ?: throw ForgeCodedException("STORY_EDIT_G2_NOT_PASSED")
        if (asMap(g2["spec"])?.get("sha256") != expectedSpecHash) throw ForgeCodedException("STORY_EDIT_G2_NOT_PASSED")
        if (storySpecHash != null && events.none {
                it["event"] == "g2_us_evaluated" && it["storyRunId"] == storyRunId && it["status"] == "passed" &&
                    asMap(it["storySpec"])?.get("sha256") == storySpecHash
            }
        ) {
            throw ForgeCodedException("STORY_EDIT_G2_US_NOT_PASSED")
        }
        val analysis = events.firstOrNull {
            it["event"] == "agent_execution_finished" && it["executionId"] == analysisExecutionId &&
                it["storyRunId"] == storyRunId && it["status"] == "finished"
        } ?: throw ForgeCodedException("STORY_EDIT_ANALYSIS_NOT_VALID")
        val validation = events.firstOrNull {
            it["event"] == "story_analysis_plan_validated" && it["executionId"] == analysisExecutionId &&
                it["status"] == "valid"
        } ?: throw ForgeCodedException("STORY_EDIT_ANALYSIS_NOT_VALID")
        val text = safeArtifact(store, asMap(analysis["artifact"]))
        val plan = ForgeStoryOperations.planFromArtifact(text)
        val repoRoot = rootsRepo(events, epicRunId)
        val specPath = asMap(g2["spec"])?.get("path") as? String
            ?: throw ForgeCodedException("STORY_EDIT_SPEC_HASH_STALE")
        val spec = io.whozoss.factory.forge.infrastructure.ForgeSpecReader.loadForgeSpec(
            specPath,
            repoRoot,
            rootsForge(events, epicRunId),
            workItem(epic["workItem"]),
        )
        if (spec.sha256 != asMap(g2["spec"])?.get("sha256")) throw ForgeCodedException("STORY_EDIT_SPEC_HASH_STALE")
        val agent = runtime.preflightAgent(namespaceId, agentName)
        if (agent["ok"] != true) throw ForgeCodedException("STORY_EDIT_AGENT_PREFLIGHT_FAILED")
        val writable = runtime.preflightWritableWorkspace(namespaceId, agent["agent"], repoRoot)
        if (writable["ok"] != true) throw ForgeCodedException("STORY_EDIT_WRITABLE_PREFLIGHT_FAILED")

        val editId = "edit_${UUID.randomUUID()}"
        val scope = asMap(spec.frontmatter["scope"]) ?: emptyMap()
        val brief = listOf(
            "Epic: ${asMap(epic["workItem"])?.get("id")}",
            "Story: ${asMap(story["workItem"])?.get("id")}",
            "Spec SHA-256: ${spec.sha256}",
            "Files to modify: ${plan.files.joinToString(", ")}",
            "Done when: ${plan.doneWhen}",
            "Allow: ${(scope["allow"] as? List<*>).orEmpty().joinToString(", ")}",
            "Create: ${(scope["create"] as? List<*>).orEmpty().joinToString(", ")}",
            "Deny: ${(scope["deny"] as? List<*>).orEmpty().joinToString(", ")}",
            supplement?.let { "Supplement: $it" } ?: "",
            "Implement only this plan. Do not run shell, git, tests, builds, or oracles.",
        ).filter { it.isNotEmpty() }.joinToString("\n")

        val before = snapshots.snapshot(repoRoot)
        val created = runtime.createCase(namespaceId, "Forge edit ${asMap(story["workItem"])?.get("id")}")
        ledgerStore.append(
            filePath.toString(),
            linkedMapOf(
                "schemaVersion" to 1,
                "event" to "story_edit_started",
                "runId" to epicRunId,
                "storyRunId" to storyRunId,
                "editId" to editId,
                "analysisExecutionId" to analysisExecutionId,
                "caseId" to created["id"],
                "policyVersion" to ForgeStoryOperations.STORY_EDIT_POLICY_VERSION,
                "at" to Instant.now().toString(),
            ),
        )
        val turn = runtime.runAgentTurn(created["id"] as? String ?: "", agentName, brief)
        val changed = snapshots.diffSince(before, repoRoot)
        val modified = changed["modified"] ?: emptyList()
        val untracked = changed["untracked"] ?: emptyList()
        val invalid = modified.filter { !ForgeStoryOperations.allowedModified(it, scope, plan) } +
            untracked.filter { !ForgeStoryOperations.allowedCreated(it, scope) }
        val status = if (turn["status"] == "finished" && invalid.isEmpty()) "finished" else "failed"
        ledgerStore.append(
            filePath.toString(),
            linkedMapOf(
                "schemaVersion" to 1,
                "event" to "story_edit_finished",
                "runId" to epicRunId,
                "storyRunId" to storyRunId,
                "editId" to editId,
                "caseId" to created["id"],
                "status" to status,
                "outcome" to turn["status"],
                "caseStatus" to turn["caseStatus"],
                "killedByBudget" to (turn["killedByBudget"] == true),
                "filesModified" to modified,
                "filesCreated" to untracked,
                "diffValidation" to mapOf(
                    "status" to if (invalid.isEmpty()) "valid" else "invalid",
                    "code" to if (invalid.isEmpty()) "STORY_EDIT_DIFF_VALID" else "STORY_EDIT_DIFF_OUT_OF_SCOPE",
                    "invalidFiles" to invalid,
                ),
                "at" to Instant.now().toString(),
            ),
        )
        return mapOf(
            "editId" to editId,
            "status" to status,
            "filesModified" to modified,
            "filesCreated" to untracked,
            "diffValidation" to mapOf("status" to if (invalid.isEmpty()) "valid" else "invalid", "invalidFiles" to invalid),
        )
    }

    // -----------------------------------------------------------------------
    // Story oracle campaign (G3)
    // -----------------------------------------------------------------------

    fun executeStoryOracles(
        runStoreRoot: String,
        epicRunId: String,
        storyRunId: String,
        editId: String,
        expectedSpecHash: String,
        attempt: Int = 1,
        hostMapRaw: String? = System.getenv("FACTORY_FRONT_BUILD_HOST_MAP"),
        frontResolver: (FrontOraclePlanInput) -> Map<String, Any?> = ForgeFrontOracleResolution::resolveFrontOraclePlan,
    ): Map<String, Any?> {
        if (attempt <= 0) throw ForgeCodedException("STORY_ORACLE_ATTEMPT_INVALID", "attempt must be a positive integer.")
        val store = store(runStoreRoot)
        val path = Path.of(store, "$epicRunId.jsonl")
        if (!Files.exists(path)) throw ForgeCodedException("STORY_ORACLE_RUN_NOT_FOUND")
        val events = ledgerStore.parse(path.toString())
        val start = events.firstOrNull { it["event"] == "run_started" && it["runId"] == epicRunId }
        val story = events.firstOrNull {
            it["event"] == "story_run_created" && it["runId"] == storyRunId && it["parentRunId"] == epicRunId
        }
        if (start == null || story == null) throw ForgeCodedException("STORY_ORACLE_STORY_NOT_FOUND")
        if (g1Outcome(events, epicRunId) != "approved") throw ForgeCodedException("STORY_ORACLE_G1_NOT_APPROVED")
        val g2 = events.filter {
            it["event"] == "g2_evaluated" && it["runId"] == epicRunId && it["status"] == "passed"
        }.lastOrNull() ?: throw ForgeCodedException("STORY_ORACLE_G2_NOT_PASSED")
        if (asMap(g2["spec"])?.get("sha256") != expectedSpecHash) throw ForgeCodedException("STORY_ORACLE_G2_NOT_PASSED")
        val edit = events.firstOrNull {
            it["event"] == "story_edit_finished" && it["editId"] == editId && it["storyRunId"] == storyRunId
        }
        if (edit == null || edit["status"] != "finished" || edit["outcome"] != "finished" ||
            asMap(edit["diffValidation"])?.get("status") != "valid"
        ) {
            throw ForgeCodedException("STORY_ORACLE_EDIT_NOT_VALID")
        }
        val repoRoot = rootsRepo(events, epicRunId)
        val specPath = asMap(g2["spec"])?.get("path") as? String
            ?: throw ForgeCodedException("STORY_ORACLE_SPEC_HASH_STALE")
        val spec = io.whozoss.factory.forge.infrastructure.ForgeSpecReader.loadForgeSpec(
            specPath,
            repoRoot,
            rootsForge(events, epicRunId),
            workItem(start["workItem"]),
        )
        if (spec.sha256 != expectedSpecHash) throw ForgeCodedException("STORY_ORACLE_SPEC_HASH_STALE")
        val oracleIds = (spec.frontmatter["oracles"] as? List<*>).orEmpty().map { it.toString() }
        val entries = ForgeStoryOperations.resolveOracleIds(oracleIds)
        val files = ((edit["filesModified"] as? List<*>).orEmpty() + (edit["filesCreated"] as? List<*>).orEmpty())
            .map { it.toString() }

        var frontPlan: Map<String, Any?>? = null
        try {
            if (entries.any { it.startsWith("front.") }) {
                frontPlan = frontResolver(
                    FrontOraclePlanInput(
                        repoRoot = repoRoot,
                        files = files,
                        hostMapRaw = hostMapRaw,
                        buildTemplate = FRONT_BUILD_TEMPLATE,
                        testsTarget = System.getenv("FACTORY_FRONT_TEST_TARGET") ?: "frontend-test",
                        requireBuild = entries.contains("front.build"),
                    ),
                )
            }
        } catch (error: ForgeCodedException) {
            val campaignId = "oracle_${UUID.randomUUID()}"
            started(path, campaignId, epicRunId, storyRunId, editId, attempt, spec.sha256)
            ledgerStore.append(
                path.toString(),
                linkedMapOf(
                    "schemaVersion" to 1,
                    "event" to "story_oracle_finished",
                    "campaignId" to campaignId,
                    "runId" to epicRunId,
                    "storyRunId" to storyRunId,
                    "editId" to editId,
                    "name" to "front.infrastructure",
                    "status" to "blocked",
                    "code" to error.code,
                    "exitCode" to null,
                    "durationMs" to 0,
                    "commandHash" to null,
                    "at" to Instant.now().toString(),
                ),
            )
            g3(path, campaignId, epicRunId, storyRunId, editId, attempt, "blocked", spec.sha256)
            return mapOf(
                "campaignId" to campaignId,
                "status" to "blocked",
                "results" to listOf(
                    mapOf(
                        "name" to "front.infrastructure",
                        "status" to "blocked",
                        "code" to error.code,
                        "exitCode" to null,
                        "durationMs" to 0,
                        "commandHash" to null,
                    ),
                ),
            )
        }

        val campaignId = "oracle_${UUID.randomUUID()}"
        started(path, campaignId, epicRunId, storyRunId, editId, attempt, spec.sha256)
        val results = mutableListOf<Map<String, Any?>>()
        for (id in entries) {
            val tests = asMap(frontPlan?.get("tests"))
            if (id == "front.tests" && tests?.get("command") == null) {
                val result = linkedMapOf<String, Any?>(
                    "name" to id,
                    "ownerProjects" to (tests?.get("owners") ?: emptyList<String>()),
                    "ownersWithTestTarget" to (tests?.get("ownersWithTestTarget") ?: emptyList<String>()),
                    "ownersWithoutTestTarget" to (tests?.get("ownersWithoutTestTarget") ?: emptyList<String>()),
                    "buildHosts" to emptyList<String>(),
                    "target" to tests?.get("target"),
                    "configuration" to null,
                    "status" to "skipped",
                    "code" to "ORACLE_NO_TEST_TARGET",
                    "exitCode" to null,
                    "durationMs" to 0,
                    "commandHash" to null,
                )
                ledgerStore.append(
                    path.toString(),
                    linkedMapOf<String, Any?>(
                        "schemaVersion" to 1,
                        "event" to "story_oracle_finished",
                        "campaignId" to campaignId,
                        "runId" to epicRunId,
                        "storyRunId" to storyRunId,
                        "editId" to editId,
                    ) + result + mapOf("at" to Instant.now().toString()),
                )
                results.add(result)
                continue
            }
            val build = asMap(frontPlan?.get("build"))
            val command = when (id) {
                "front.build" -> build?.get("command") as? String
                "front.tests" -> tests?.get("command") as? String
                else -> null
            }
            if (command == null) {
                val result = linkedMapOf<String, Any?>(
                    "name" to id,
                    "status" to "blocked",
                    "code" to "ORACLE_INFRASTRUCTURE",
                    "exitCode" to null,
                    "durationMs" to 0,
                    "commandHash" to null,
                )
                ledgerStore.append(
                    path.toString(),
                    linkedMapOf<String, Any?>(
                        "schemaVersion" to 1,
                        "event" to "story_oracle_finished",
                        "campaignId" to campaignId,
                        "runId" to epicRunId,
                        "storyRunId" to storyRunId,
                        "editId" to editId,
                    ) + result + mapOf("at" to Instant.now().toString()),
                )
                results.add(result)
                break
            }
            val raw = try {
                executor.run(command, repoRoot, 20 * 60 * 1000L)
            } catch (_: Exception) {
                OracleCommandResult(exitCode = -1, durationMs = 0, crashed = true)
            }
            val status = if (raw.timedOut || raw.crashed) "blocked" else if (raw.exitCode == 0) "passed" else "failed"
            val result = linkedMapOf<String, Any?>(
                "name" to id,
                "ownerProjects" to if (id == "front.tests") tests?.get("owners") else (frontPlan?.get("owners") ?: emptyList<String>()),
                "ownersWithTestTarget" to (tests?.get("ownersWithTestTarget") ?: emptyList<String>()),
                "ownersWithoutTestTarget" to (tests?.get("ownersWithoutTestTarget") ?: emptyList<String>()),
                "buildHosts" to if (id == "front.build") (build?.get("buildHosts") ?: emptyList<String>()) else emptyList<String>(),
                "target" to if (id == "front.build") "build" else if (id == "front.tests") tests?.get("target") else null,
                "configuration" to if (id == "front.build") "development" else null,
                "status" to status,
                "code" to if (raw.timedOut) "ORACLE_TIMEOUT" else if (raw.crashed) "ORACLE_CRASH" else if (raw.exitCode == 0) "ORACLE_PASS" else "ORACLE_FAIL",
                "exitCode" to raw.exitCode,
                "durationMs" to raw.durationMs,
                "commandHash" to io.whozoss.factory.forge.domain.ForgeSpec.computeForgeSpecHash(command),
            )
            ledgerStore.append(
                path.toString(),
                linkedMapOf<String, Any?>(
                    "schemaVersion" to 1,
                    "event" to "story_oracle_finished",
                    "campaignId" to campaignId,
                    "runId" to epicRunId,
                    "storyRunId" to storyRunId,
                    "editId" to editId,
                ) + result + mapOf("at" to Instant.now().toString()),
            )
            results.add(result)
            if (status != "passed") break
        }
        val complete = results.size == entries.size && results.withIndex().all { (index, result) ->
            result["name"] == entries[index] && (result["status"] == "passed" || result["code"] == "ORACLE_NO_TEST_TARGET")
        }
        val status = when {
            results.any { it["status"] == "blocked" } -> "blocked"
            complete -> "passed"
            else -> "failed"
        }
        g3(path, campaignId, epicRunId, storyRunId, editId, attempt, status, spec.sha256)
        return mapOf("campaignId" to campaignId, "status" to status, "results" to results)
    }

    private fun started(
        path: Path,
        campaignId: String,
        epicRunId: String,
        storyRunId: String,
        editId: String,
        attempt: Int,
        specHash: String,
    ) {
        ledgerStore.append(
            path.toString(),
            linkedMapOf(
                "schemaVersion" to 1,
                "event" to "story_oracles_started",
                "campaignId" to campaignId,
                "runId" to epicRunId,
                "storyRunId" to storyRunId,
                "editId" to editId,
                "attempt" to attempt,
                "specHash" to specHash,
                "policyVersion" to ForgeStoryOperations.STORY_ORACLE_POLICY_VERSION,
                "at" to Instant.now().toString(),
            ),
        )
    }

    private fun g3(
        path: Path,
        campaignId: String,
        epicRunId: String,
        storyRunId: String,
        editId: String,
        attempt: Int,
        status: String,
        specHash: String,
    ) {
        ledgerStore.append(
            path.toString(),
            linkedMapOf(
                "schemaVersion" to 1,
                "event" to "story_g3_evaluated",
                "campaignId" to campaignId,
                "runId" to epicRunId,
                "storyRunId" to storyRunId,
                "editId" to editId,
                "attempt" to attempt,
                "status" to status,
                "specHash" to specHash,
                "policyVersion" to ForgeStoryOperations.STORY_ORACLE_POLICY_VERSION,
                "at" to Instant.now().toString(),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun safeArtifact(store: String, descriptor: Map<String, Any?>?): String {
        val path = descriptor?.get("path") as? String
        val sha = descriptor?.get("sha256") as? String
        if (path == null || sha == null) {
            throw ForgeCodedException("STORY_EDIT_ANALYSIS_ARTIFACT_INVALID")
        }
        val root = Path.of(store).toAbsolutePath().normalize()
        val resolved = root.resolve(path).normalize()
        if (!resolved.startsWith(root)) throw ForgeCodedException("STORY_EDIT_ANALYSIS_ARTIFACT_PATH_INVALID")
        if (!Files.exists(resolved)) throw ForgeCodedException("STORY_EDIT_ANALYSIS_ARTIFACT_INVALID")
        val text = Files.readString(resolved)
        if (io.whozoss.factory.forge.domain.ForgeSpec.computeForgeSpecHash(text) != sha) {
            throw ForgeCodedException("STORY_EDIT_ANALYSIS_ARTIFACT_HASH_MISMATCH")
        }
        return text
    }

    private fun g1Outcome(events: List<Map<String, Any?>>, runId: String): String? =
        events.firstOrNull { it["event"] == "human_decision_recorded" && it["runId"] == runId && it["gate"] == "G1" }
            ?.let { asMap(it["decision"])?.get("outcome") as? String }

    private fun rootsRepo(events: List<Map<String, Any?>>, runId: String): String =
        events.firstOrNull { it["event"] == "run_started" && it["runId"] == runId }
            ?.let { asMap(it["roots"])?.get("repoRoot") as? String } ?: ""

    private fun rootsForge(events: List<Map<String, Any?>>, runId: String): String? =
        events.firstOrNull { it["event"] == "run_started" && it["runId"] == runId }
            ?.let { asMap(it["roots"])?.get("forgeRoot") as? String }

    private fun workItem(raw: Any?) = io.whozoss.factory.forge.domain.ForgeWorkItem(
        id = asMap(raw)?.get("id") as? String ?: "",
        kind = asMap(raw)?.get("kind") as? String ?: "",
    )
}
