package io.whozoss.factory.forge.domain

/**
 * Pure Forge ledger domain: JSONL parsing/validation and the deterministic
 * replay (`projectForgeRun`) that derives the dashboard's display state from
 * the append-only journal.
 *
 * Port of `factory/src/domain/forge-bmad/forge-ledger.ts`. The file-backed
 * store lives in [io.whozoss.factory.forge.infrastructure.FileForgeLedgerStore].
 */
object ForgeLedger {

    /** Schema version of the Forge ledger. */
    const val FORGE_LEDGER_SCHEMA_VERSION = 1

    /** Workflow version stamped on an EpicRun. */
    const val FORGE_WORKFLOW_VERSION = "forge-epic-v1"

    /**
     * Parse an append-only JSONL ledger body. Throws on malformed JSON or an
     * unsupported schema version.
     */
    fun parseForgeLedgerLines(raw: String): List<ForgeLedgerEvent> =
        raw.split("\n")
            .filter { it.isNotEmpty() }
            .mapIndexed { index, line ->
                val event = try {
                    ForgeJson.parseObject(line)
                } catch (error: Exception) {
                    throw IllegalArgumentException("invalid JSONL at line ${index + 1}", error)
                }
                if (asInt(event["schemaVersion"]) != FORGE_LEDGER_SCHEMA_VERSION) {
                    throw IllegalArgumentException("unsupported forge ledger schema at line ${index + 1}")
                }
                event
            }

    /** Pure replay: the current display state is entirely derived from JSONL. */
    fun projectForgeRun(events: List<ForgeLedgerEvent>): Map<String, Any?>? {
        val start = events.firstOrNull { it["event"] == "run_started" && it["runType"] == "EpicRun" } ?: return null
        val runId = start["runId"] as? String ?: return null
        val storyEvents = events
            .filter { it["event"] == "story_run_created" && it["parentRunId"] == runId }
            .sortedBy { asInt(it["ordinal"]) }

        val g1 = events
            .filter { it["event"] == "gate_started" && it["runId"] == runId && it["gate"] == "G1" }
            .lastOrNull()
        val decision = g1?.let { gate ->
            events.firstOrNull { event ->
                event["event"] == "human_decision_recorded" &&
                    event["runId"] == runId &&
                    event["gate"] == "G1" &&
                    asInt(event["attempt"]) == asInt(gate["attempt"])
            }
        }
        val evidenceSetHash = g1?.let {
            ForgeHumanDecision.computeG1EvidenceSetHash(
                events,
                runId,
                asInt(it["attempt"]),
                it["policyVersion"] as? String ?: ForgeHumanDecision.G1_POLICY_VERSION,
            )
        }
        val decisionOutcome = asMap(decision?.get("decision"))?.get("outcome")
        val g1Status = (decisionOutcome as? String) ?: (g1?.get("status") as? String ?: "not_started")
        val g2 = events.filter { it["event"] == "g2_evaluated" && it["runId"] == runId }.lastOrNull()

        val validations = HashMap<String, ForgeLedgerEvent>()
        events.filter { it["event"] == "story_analysis_plan_validated" }.forEach { event ->
            (event["executionId"] as? String)?.let { validations[it] = event }
        }

        val oracleCampaignsByStory = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        events.filter { it["event"] == "story_g3_evaluated" }.forEach { gate ->
            val results = events
                .filter { it["event"] == "story_oracle_finished" && it["campaignId"] == gate["campaignId"] }
                .map { event ->
                    linkedMapOf<String, Any?>(
                        "name" to event["name"],
                        "status" to event["status"],
                        "code" to event["code"],
                        "ownerProjects" to (event["ownerProjects"] ?: emptyList<String>()),
                        "target" to (event["target"] ?: null),
                        "buildHosts" to (event["buildHosts"] ?: emptyList<String>()),
                        "ownersWithTestTarget" to (event["ownersWithTestTarget"] ?: emptyList<String>()),
                        "ownersWithoutTestTarget" to (event["ownersWithoutTestTarget"] ?: emptyList<String>()),
                        "exitCode" to event["exitCode"],
                        "durationMs" to event["durationMs"],
                        "commandHash" to event["commandHash"],
                    )
                }
            val storyRunId = gate["storyRunId"] as? String ?: return@forEach
            oracleCampaignsByStory.getOrPut(storyRunId) { mutableListOf() }.add(
                linkedMapOf(
                    "campaignId" to gate["campaignId"],
                    "editId" to gate["editId"],
                    "status" to gate["status"],
                    "specHash" to gate["specHash"],
                    "policyVersion" to gate["policyVersion"],
                    "results" to results,
                ),
            )
        }

        val editsByStory = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        events.filter { it["event"] == "story_edit_finished" }.forEach { edit ->
            val storyRunId = edit["storyRunId"] as? String ?: return@forEach
            editsByStory.getOrPut(storyRunId) { mutableListOf() }.add(
                linkedMapOf(
                    "editId" to edit["editId"],
                    "status" to edit["status"],
                    "outcome" to edit["outcome"],
                    "caseId" to edit["caseId"],
                    "diffValidation" to edit["diffValidation"],
                    "filesModified" to edit["filesModified"],
                    "filesCreated" to edit["filesCreated"],
                ),
            )
        }

        val g2usByStory = LinkedHashMap<String, ForgeLedgerEvent>()
        events.filter { it["event"] == "g2_us_evaluated" }.forEach { event ->
            (event["storyRunId"] as? String)?.let { g2usByStory[it] = event }
        }

        val executionsByStory = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        events.filter { it["event"] == "agent_execution_finished" }.forEach { execution ->
            val storyRunId = execution["storyRunId"] as? String ?: return@forEach
            val validation = (execution["executionId"] as? String)?.let { validations[it] }
            executionsByStory.getOrPut(storyRunId) { mutableListOf() }.add(
                linkedMapOf(
                    "executionId" to execution["executionId"],
                    "caseId" to execution["caseId"],
                    "runtime" to execution["runtime"],
                    "role" to execution["role"],
                    "agentName" to execution["agentName"],
                    "namespaceId" to execution["namespaceId"],
                    "status" to execution["status"],
                    "outcome" to execution["outcome"],
                    "caseStatus" to (execution["caseStatus"] ?: null),
                    "killedByBudget" to (execution["killedByBudget"] == true),
                    "artifact" to (execution["artifact"] ?: null),
                    "analysisValidation" to (
                        validation?.let {
                            linkedMapOf(
                                "schemaVersion" to it["planSchemaVersion"],
                                "status" to it["status"],
                                "code" to it["code"],
                            )
                        }
                        ),
                    "observedAt" to execution["observedAt"],
                ),
            )
        }

        val gates = mutableListOf<Map<String, Any?>>()
        if (g1 != null) {
            gates.add(
                linkedMapOf(
                    "gate" to "G1",
                    "attempt" to g1["attempt"],
                    "status" to g1Status,
                    "requiredDecision" to g1["requiredDecision"],
                    "policyVersion" to g1["policyVersion"],
                    "evidenceSetHash" to evidenceSetHash,
                    "decision" to (decision?.get("decision") ?: null),
                ),
            )
        }
        if (g2 != null) {
            gates.add(
                linkedMapOf(
                    "gate" to "G2",
                    "attempt" to g2["attempt"],
                    "status" to g2["status"],
                    "code" to g2["code"],
                    "policyVersion" to g2["policyVersion"],
                    "spec" to (g2["spec"] ?: null),
                ),
            )
        }

        val stories = storyEvents.map { event ->
            val storyRunId = event["runId"] as? String ?: ""
            val executions = executionsByStory[storyRunId] ?: emptyList()
            val edits = editsByStory[storyRunId] ?: emptyList()
            val oracleCampaigns = oracleCampaignsByStory[storyRunId] ?: emptyList()
            val g2usEvent = g2usByStory[storyRunId]
            val storyG2 = g2usEvent?.let {
                linkedMapOf(
                    "gate" to "G2-US",
                    "attempt" to it["attempt"],
                    "status" to it["status"],
                    "code" to it["code"],
                    "policyVersion" to it["policyVersion"],
                    "storySpec" to (it["storySpec"] ?: null),
                )
            }
            // Event order is the ledger's authoritative chronology. A Story's
            // visible state is its latest completed workflow step.
            val latestCampaign = oracleCampaigns.lastOrNull()
            val latestEdit = edits.lastOrNull()
            val latestExecution = executions.lastOrNull()
            val status = latestCampaign?.get("status")
                ?: latestEdit?.get("status")
                ?: latestExecution?.get("status")
                ?: "not_started"
            linkedMapOf(
                "runId" to event["runId"],
                "ordinal" to event["ordinal"],
                "status" to status,
                "workItem" to event["workItem"],
                "executions" to executions,
                "edits" to edits,
                "oracleCampaigns" to oracleCampaigns,
                "storyG2" to storyG2,
            )
        }

        return linkedMapOf(
            "schemaVersion" to FORGE_LEDGER_SCHEMA_VERSION,
            "runId" to start["runId"],
            "runType" to start["runType"],
            "workflow" to start["workflow"],
            "workItem" to start["workItem"],
            "roots" to start["roots"],
            "startedAt" to start["at"],
            "status" to g1Status,
            "gates" to gates,
            "stories" to stories,
        )
    }
}

/** Top-level aliases matching the Node export surface. */
const val FORGE_LEDGER_SCHEMA_VERSION = ForgeLedger.FORGE_LEDGER_SCHEMA_VERSION
const val FORGE_WORKFLOW_VERSION = ForgeLedger.FORGE_WORKFLOW_VERSION

fun parseForgeLedgerLines(raw: String): List<ForgeLedgerEvent> = ForgeLedger.parseForgeLedgerLines(raw)

fun projectForgeRun(events: List<ForgeLedgerEvent>): Map<String, Any?>? = ForgeLedger.projectForgeRun(events)
