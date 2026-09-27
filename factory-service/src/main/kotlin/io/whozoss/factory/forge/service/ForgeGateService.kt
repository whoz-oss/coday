package io.whozoss.factory.forge.service

import io.whozoss.factory.forge.domain.ForgeCodedException
import io.whozoss.factory.forge.domain.ForgeRoots
import io.whozoss.factory.forge.domain.ForgeStorySpec
import io.whozoss.factory.forge.domain.ForgeWorkItem
import io.whozoss.factory.forge.domain.G2_POLICY_VERSION
import io.whozoss.factory.forge.domain.G2_US_POLICY_VERSION
import io.whozoss.factory.forge.domain.asInt
import io.whozoss.factory.forge.domain.asMap
import io.whozoss.factory.forge.infrastructure.ForgeRootsResolver
import io.whozoss.factory.forge.infrastructure.ForgeSpecReader
import io.whozoss.factory.forge.infrastructure.LoadedForgeSpec
import io.whozoss.factory.forge.port.ForgeLedgerStore
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant

/**
 * Application service for the deterministic G2 (Epic spec) and G2-US (Story
 * spec) gates.
 *
 * Port of `factory/src/application/forge-bmad/forge-g2.ts`. Spec reads live in
 * [ForgeSpecReader] and the append-only ledger access in [ForgeLedgerStore].
 */
@Service
class ForgeGateService(
    private val ledgerStore: ForgeLedgerStore,
) {

    private fun ledgerFile(roots: ForgeRoots, runId: String): String {
        val store = ForgeRootsResolver.ensureForgeRunStore(roots.runStoreRoot)
        return Path.of(store, "$runId.jsonl").toString()
    }

    private fun gate(events: List<Map<String, Any?>>, runId: String, name: String): Map<String, Any?>? =
        events.filter { it["event"] == "gate_started" && it["runId"] == runId && it["gate"] == name }.lastOrNull()

    private fun g1Status(events: List<Map<String, Any?>>, runId: String): String {
        val g1 = gate(events, runId, "G1")
        val decision = g1?.let { started ->
            events.firstOrNull { event ->
                event["event"] == "human_decision_recorded" && event["runId"] == runId &&
                    event["gate"] == "G1" && asInt(event["attempt"]) == asInt(started["attempt"])
            }
        }
        return asMap(decision?.get("decision"))?.get("outcome") as? String
            ?: g1?.get("status") as? String
            ?: "missing"
    }

    /** Evaluate the Epic G2 gate against a spec file. */
    fun evaluateG2(roots: Map<String, Any?>, runId: String, specPath: String): Map<String, Any?> {
        val resolved = ForgeRoots.fromMap(roots)
        val filePath = ledgerFile(resolved, runId)
        val events = ledgerStore.parse(filePath)
        val start = events.firstOrNull { it["event"] == "run_started" && it["runId"] == runId }
            ?: throw ForgeCodedException("G2_RUN_NOT_FOUND")
        val prior = events.filter { it["event"] == "g2_evaluated" && it["runId"] == runId }.lastOrNull()
        val spec = try {
            ForgeSpecReader.loadForgeSpec(specPath, resolved.repoRoot, resolved.forgeRoot, workItem(start["workItem"]))
        } catch (error: ForgeCodedException) {
            return record(filePath, runId, prior, null, "blocked", error.code)
        }
        if (prior?.get("status") == "passed" &&
            asMap(prior?.get("spec"))?.get("sha256") == spec.sha256 &&
            prior?.get("policyVersion") == G2_POLICY_VERSION
        ) {
            return mapOf("status" to "idempotent", "event" to prior)
        }
        if (prior?.get("status") == "passed" && asMap(prior["spec"])?.get("sha256") != spec.sha256) {
            return mapOf("status" to "conflict", "code" to "G2_SPEC_HASH_CHANGED", "event" to prior)
        }
        if (g1Status(events, runId) != "approved") {
            return record(filePath, runId, prior, spec, "blocked", "G2_G1_NOT_APPROVED")
        }
        return record(filePath, runId, prior, spec, "passed", "G2_SPEC_VALID")
    }

    private fun record(
        filePath: String,
        runId: String,
        prior: Map<String, Any?>?,
        spec: LoadedForgeSpec?,
        status: String,
        code: String,
    ): Map<String, Any?> {
        val attempt = asInt(prior?.get("attempt")) + 1
        val event = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "event" to "g2_evaluated",
            "runId" to runId,
            "gate" to "G2",
            "attempt" to attempt,
            "status" to status,
            "code" to code,
            "policyVersion" to G2_POLICY_VERSION,
            "spec" to spec?.let {
                linkedMapOf("path" to it.path, "sha256" to it.sha256, "schemaVersion" to it.schemaVersion)
            },
            "at" to Instant.now().toString(),
        )
        ledgerStore.append(filePath, event)
        return mapOf("status" to "recorded", "event" to event)
    }

    /** Evaluate the Story G2-US gate against a spec file, inheriting from the Epic. */
    fun evaluateG2US(
        roots: Map<String, Any?>,
        epicRunId: String,
        storyRunId: String,
        storySpecPath: String,
    ): Map<String, Any?> {
        val resolved = ForgeRoots.fromMap(roots)
        val filePath = ledgerFile(resolved, epicRunId)
        val events = ledgerStore.parse(filePath)
        val prior = events.filter { it["event"] == "g2_us_evaluated" && it["storyRunId"] == storyRunId }.lastOrNull()

        val epicStart = events.firstOrNull { it["event"] == "run_started" && it["runId"] == epicRunId }
            ?: return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", "G2_US_EPIC_RUN_NOT_FOUND")
        val storyRun = events.firstOrNull {
            it["event"] == "story_run_created" && it["runId"] == storyRunId && it["parentRunId"] == epicRunId
        } ?: return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", "G2_US_STORY_RUN_NOT_FOUND")
        if (g1Status(events, epicRunId) != "approved") {
            return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", "G2_US_G1_NOT_APPROVED")
        }
        val g2EpicEvent = events.filter {
            it["event"] == "g2_evaluated" && it["runId"] == epicRunId && it["status"] == "passed"
        }.lastOrNull() ?: return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", "G2_US_G2_NOT_PASSED")

        val storySpec = try {
            ForgeSpecReader.readStorySpec(storySpecPath, resolved.repoRoot, resolved.forgeRoot)
        } catch (error: ForgeCodedException) {
            return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", error.code)
        }
        if (asMap(prior?.get("storySpec"))?.get("sha256") == storySpec.sha256 &&
            prior?.get("policyVersion") == G2_US_POLICY_VERSION &&
            prior?.get("code") != "G2_US_G1_NOT_APPROVED" && prior?.get("code") != "G2_US_G2_NOT_PASSED"
        ) {
            return mapOf("status" to "idempotent", "event" to prior)
        }
        if (prior?.get("status") == "passed" && asMap(prior["storySpec"])?.get("sha256") != storySpec.sha256) {
            return mapOf("status" to "conflict", "code" to "G2_US_SPEC_HASH_CHANGED", "event" to prior)
        }
        if (asMap(storySpec.frontmatter["workItem"])?.get("id") != asMap(storyRun["workItem"])?.get("id")) {
            return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, "blocked", "G2_US_WORK_ITEM_MISMATCH")
        }
        val epicSpecPath = asMap(g2EpicEvent["spec"])?.get("path") as? String
            ?: return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, "blocked", "G2_US_SPEC_INVALID")
        val epicSpec = try {
            ForgeSpecReader.loadForgeSpec(epicSpecPath, resolved.repoRoot, resolved.forgeRoot, workItem(epicStart["workItem"]))
        } catch (error: ForgeCodedException) {
            return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, "blocked", error.code)
        }
        val inheritance = ForgeStorySpec.validateInheritance(storySpec.frontmatter, epicSpec.frontmatter)
        if (!inheritance.valid) {
            return recordUS(
                filePath,
                epicRunId,
                storyRunId,
                storySpec,
                prior,
                "blocked",
                "G2_US_INHERITANCE_VIOLATION",
                inheritance.violations.map { mapOf("code" to it.code, "detail" to it.detail) },
            )
        }
        return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, "passed", "G2_US_SPEC_VALID")
    }

    private fun recordUS(
        filePath: String,
        epicRunId: String,
        storyRunId: String,
        storySpec: LoadedForgeSpec?,
        prior: Map<String, Any?>?,
        status: String,
        code: String,
        violations: List<Map<String, Any?>>? = null,
    ): Map<String, Any?> {
        val attempt = asInt(prior?.get("attempt")) + 1
        val event = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "event" to "g2_us_evaluated",
            "runId" to epicRunId,
            "storyRunId" to storyRunId,
            "gate" to "G2-US",
            "attempt" to attempt,
            "status" to status,
            "code" to code,
            "policyVersion" to G2_US_POLICY_VERSION,
            "storySpec" to storySpec?.let {
                linkedMapOf("path" to it.path, "sha256" to it.sha256, "schemaVersion" to it.schemaVersion)
            },
        )
        if (violations != null) event["violations"] = violations
        event["at"] = Instant.now().toString()
        ledgerStore.append(filePath, event)
        return mapOf("status" to "recorded", "event" to event)
    }

    private fun workItem(raw: Any?): ForgeWorkItem {
        val map = asMap(raw)
        return ForgeWorkItem(id = map?.get("id") as? String ?: "", kind = map?.get("kind") as? String ?: "")
    }
}
