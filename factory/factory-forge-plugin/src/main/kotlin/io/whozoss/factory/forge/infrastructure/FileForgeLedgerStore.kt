package io.whozoss.factory.forge.infrastructure

import io.whozoss.factory.forge.domain.FORGE_LEDGER_SCHEMA_VERSION
import io.whozoss.factory.forge.domain.FORGE_WORKFLOW_VERSION
import io.whozoss.factory.forge.domain.ForgeHumanDecision
import io.whozoss.factory.forge.domain.ForgeJson
import io.whozoss.factory.forge.domain.ForgeLedger
import io.whozoss.factory.forge.domain.ForgeLedgerEvent
import io.whozoss.factory.forge.port.CreateEpicRunRequest
import io.whozoss.factory.forge.port.CreateEpicRunResult
import io.whozoss.factory.forge.port.ForgeLedgerStore
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * Filesystem adapter for the Forge ledger store.
 *
 * Port of `factory/src/adapters/forge/forge-ledger-store.ts`. The append-only
 * JSONL parsing and deterministic replay are pure and live in
 * [ForgeLedger]; this adapter owns the file boundary (`appendFileSync`,
 * `readFileSync`, `readdirSync`) and the store creation.
 *
 * Appends use `StandardOpenOption.APPEND` so concurrent writers never truncate
 * the journal; a `synchronized` guard on the JVM makes each append atomic for
 * this process.
 */
@Component
class FileForgeLedgerStore : ForgeLedgerStore {

    private val appendLock = Any()

    private fun assertString(value: Any?, name: String) {
        if (value !is String || value.isBlank()) throw IllegalArgumentException("$name is required")
    }

    private fun assertWorkItem(item: Map<String, Any?>?, name: String) {
        if (item == null) throw IllegalArgumentException("$name is required")
        assertString(item["id"], "$name.id")
        assertString(item["kind"], "$name.kind")
    }

    override fun append(filePath: String, event: ForgeLedgerEvent) {
        val line = ForgeJson.stringify(event) + "\n"
        synchronized(appendLock) {
            Files.writeString(
                Path.of(filePath),
                line,
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    override fun createEpicRun(request: CreateEpicRunRequest): CreateEpicRunResult {
        assertWorkItem(request.epic, "epic")
        if (request.stories.isEmpty()) {
            throw IllegalArgumentException("stories must contain at least one explicit Story work item")
        }
        for (story in request.stories) {
            assertWorkItem(story, "story")
            if (story["kind"] != "Story") throw IllegalArgumentException("every child work item must have kind \"Story\"")
        }

        val runStoreRoot = ForgeRootsResolver.ensureForgeRunStore(request.roots.runStoreRoot)
        val runId = request.runId ?: "epic_${UUID.randomUUID()}"
        val filePath = Path.of(runStoreRoot, "$runId.jsonl").toString()
        val now = { Instant.now().toString() }
        val at = now()

        append(
            filePath,
            linkedMapOf(
                "schemaVersion" to FORGE_LEDGER_SCHEMA_VERSION,
                "event" to "run_started",
                "runId" to runId,
                "runType" to "EpicRun",
                "workflow" to FORGE_WORKFLOW_VERSION,
                "workItem" to request.epic,
                "roots" to request.roots.toMap(),
                "at" to at,
            ),
        )

        val storyRuns = request.stories.mapIndexed { index, workItem ->
            val storyRunId = "story_${UUID.randomUUID()}"
            append(
                filePath,
                linkedMapOf(
                    "schemaVersion" to FORGE_LEDGER_SCHEMA_VERSION,
                    "event" to "story_run_created",
                    "runId" to storyRunId,
                    "parentRunId" to runId,
                    "runType" to "StoryRun",
                    "ordinal" to index + 1,
                    "workItem" to workItem,
                    "at" to now(),
                ),
            )
            linkedMapOf<String, Any?>(
                "runId" to storyRunId,
                "parentRunId" to runId,
                "ordinal" to index + 1,
                "workItem" to workItem,
            )
        }

        append(
            filePath,
            linkedMapOf(
                "schemaVersion" to FORGE_LEDGER_SCHEMA_VERSION,
                "event" to "gate_started",
                "runId" to runId,
                "gate" to "G1",
                "attempt" to 1,
                "status" to "waiting_human",
                "requiredDecision" to "intent-approval",
                "policyVersion" to ForgeHumanDecision.G1_POLICY_VERSION,
                "at" to now(),
            ),
        )

        return CreateEpicRunResult(runId = runId, filePath = filePath, storyRuns = storyRuns)
    }

    override fun parse(filePath: String): List<ForgeLedgerEvent> =
        ForgeLedger.parseForgeLedgerLines(Files.readString(Path.of(filePath)))

    override fun listProjections(runStoreRoot: String): List<Map<String, Any?>> {
        val root = Path.of(runStoreRoot)
        if (!Files.isDirectory(root)) return emptyList()
        val files = Files.list(root).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jsonl") }.toList()
        }
        return files.mapNotNull { file ->
            try {
                ForgeLedger.projectForgeRun(parse(file.toString()))
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it["startedAt"] as? String ?: "" }
    }
}
