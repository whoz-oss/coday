package io.whozoss.factory.runs.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.web.RawSseEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Legacy workflow JSONL runs, review gates and live SSE streaming.
 *
 * Port of `factory/dashboard/run-routes.mjs`. The run registry stays in-process
 * (active child processes) while the durable state is the append-only JSONL
 * ledger under [runsDir]; the ledger is never stored in PostgreSQL.
 */
class LegacyRunService(
    private val runsDir: String,
    private val runEntry: String,
    private val agentosUrl: String,
    private val jiraBaseUrl: String?,
    private val jiraEmail: String?,
    private val jiraApiToken: String?,
) : ApplicationListener<ContextClosedEvent> {

    private val mapper = ObjectMapper()

    private class PendingGate(
        val runId: String,
        val gateInstanceId: String,
        val gateType: String,
        val findings: Any?,
        val outcomes: Any?,
        val oracleGate: Any?,
        val openedAt: String,
    )

    private class ActiveRun(
        @Volatile var child: Process?,
        @Volatile var stopping: Boolean = false,
        val gateIpcSecret: String,
        @Volatile var trackedRunId: String? = null,
        val lines: MutableList<String> = CopyOnWriteArrayList(),
        val listeners: MutableSet<SseEmitter> = ConcurrentHashMap.newKeySet(),
    )

    private val activeRuns = ConcurrentHashMap<String, ActiveRun>()
    private val pendingGates = ConcurrentHashMap<String, PendingGate>()
    private val random = SecureRandom()

    // -----------------------------------------------------------------------
    // Pure JSONL helpers
    // -----------------------------------------------------------------------

    fun parseJsonl(filePath: String): List<Map<String, Any?>> {
        val content = try {
            Files.readString(Path.of(filePath))
        } catch (_: Exception) {
            return emptyList()
        }
        return content.split("\n")
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                try {
                    mapper.readValue(line, object : TypeReference<LinkedHashMap<String, Any?>>() {})
                } catch (_: Exception) {
                    null
                }
            }
    }

    fun reconstructPhases(lines: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val phaseOrder = mutableListOf<String>()
        val phaseStarts = HashMap<String, Map<String, Any?>>()
        val phaseEnds = HashMap<String, Map<String, Any?>>()
        for (line in lines) {
            val name = line["name"] as? String ?: continue
            when (line["kind"]) {
                "phase" -> {
                    if (!phaseStarts.containsKey(name)) phaseOrder.add(name)
                    phaseStarts[name] = line
                }

                "phase_end" -> phaseEnds[name] = line
            }
        }
        return phaseOrder.map { name ->
            val start = phaseStarts[name]
            val end = phaseEnds[name]
            linkedMapOf<String, Any?>(
                "name" to name,
                "phaseKind" to (start?.get("phaseKind") ?: "?"),
                "status" to (end?.get("status") ?: "running"),
                "startedAt" to start?.get("startedAt"),
                "durationMs" to end?.get("durationMs"),
                "facts" to (end?.get("facts") ?: emptyMap<String, Any?>()),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Summaries / details
    // -----------------------------------------------------------------------

    private fun extractContext(lines: List<Map<String, Any?>>): Map<String, Any?> {
        val ctx = LinkedHashMap<String, Any?>()
        for (line in lines) {
            if (line["kind"] != "phase_end") continue
            val facts = (line["facts"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            fun put(key: String, factKey: String) {
                if (!ctx.containsKey(key) && facts[factKey] != null) ctx[key] = facts[factKey]
            }
            put("domain", "domain")
            put("rootPath", "rootPath")
            put("command", "command")
            put("ticketId", "ticketId")
            put("ticketSummary", "summary")
            val agentName = facts["agentName"]
            if (agentName != null && !ctx.containsKey("roles")) ctx["roles"] = listOf(agentName)
            val analystName = facts["analystName"]
            val editorName = facts["editorName"]
            if (analystName != null || editorName != null) {
                ctx["roles"] = listOfNotNull(analystName, editorName)
            }
        }
        return ctx
    }

    fun summarizeRun(runId: String): Map<String, Any?> {
        val lines = parseJsonl(Path.of(runsDir, "$runId.jsonl").toString())
        val start = lines.firstOrNull { it["kind"] == "run_start" }
        val end = lines.firstOrNull { it["kind"] == "run_end" }
        val phaseEnds = lines.filter { it["kind"] == "phase_end" }
        val status = when {
            end != null -> end["status"]
            !activeRuns.containsKey(runId) -> "crashed"
            else -> "running"
        }
        val namespaceId = start?.get("namespaceId")
        val summary = linkedMapOf<String, Any?>(
            "runId" to runId,
            "workflow" to (start?.get("workflow") ?: "?"),
            "startedAt" to start?.get("startedAt"),
            "endedAt" to end?.get("endedAt"),
            "durationMs" to end?.get("durationMs"),
            "status" to status,
            "phaseCount" to phaseEnds.size,
            "context" to extractContext(lines),
        )
        if (namespaceId != null) summary["namespaceId"] = namespaceId
        return summary
    }

    fun detailRun(runId: String): Map<String, Any?>? {
        val filePath = Path.of(runsDir, "$runId.jsonl")
        if (!Files.exists(filePath)) return null
        val lines = parseJsonl(filePath.toString())
        val summary = summarizeRun(runId)
        val phases = reconstructPhases(lines)
        val logLines = activeRuns[runId]?.lines ?: emptyList()
        return summary + mapOf("phases" to phases, "logLines" to logLines)
    }

    fun listRuns(): List<Map<String, Any?>> {
        val dir = Path.of(runsDir)
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".jsonl") }.toList()
        }
        return files.sortedDescending().map { summarizeRun(it.removeSuffix(".jsonl")) }
    }

    // -----------------------------------------------------------------------
    // Launch / stop
    // -----------------------------------------------------------------------

    /** Spawn `node <runEntry> <workflow>`; returns `{pid}` or `{error}`. */
    fun launchRun(params: Map<String, Any?>): Map<String, Any?> {
        val workflow = params["workflow"] as? String ?: "fix-loop"
        val itemsWithSingleRole = setOf("fix-loop", "smoke", "agentos-smoke")
        val itemsWithoutAgent = setOf("backend-oracle-check", "verify-back")
        val factoryNamespaceId = params["FACTORY_NAMESPACE_ID"] as? String
        val factoryTask = params["FACTORY_TASK"] as? String
        val factoryAgent = params["FACTORY_AGENT"] as? String

        if (factoryNamespaceId.isNullOrEmpty() && workflow !in itemsWithoutAgent) {
            return mapOf("error" to "FACTORY_NAMESPACE_ID manquant")
        }
        if (factoryTask.isNullOrEmpty() && workflow !in itemsWithoutAgent) {
            return mapOf("error" to "FACTORY_TASK manquant")
        }
        if (workflow in itemsWithSingleRole && factoryAgent.isNullOrEmpty()) {
            return mapOf("error" to "FACTORY_AGENT manquant (requis par \"$workflow\")")
        }
        val factoryTicket = params["FACTORY_TICKET"] as? String
        if (!factoryTicket.isNullOrEmpty()) {
            val missing = buildList {
                if (jiraBaseUrl.isNullOrBlank()) add("JIRA_BASE_URL")
                if (jiraEmail.isNullOrBlank()) add("JIRA_EMAIL")
                if (jiraApiToken.isNullOrBlank()) add("JIRA_API_TOKEN")
            }
            if (missing.isNotEmpty()) {
                return mapOf(
                    "error" to "Le serveur du dashboard n'a pas de credentials Jira configurés " +
                        "(manquant : ${missing.joinToString(", ")}). " +
                        "Relancez-le avec ces variables dans son environnement : " +
                        "JIRA_BASE_URL=https://votre-instance.atlassian.net " +
                        "JIRA_EMAIL=votre@email.com " +
                        "JIRA_API_TOKEN=votre-token " +
                        "node factory/dashboard/server.mjs",
                )
            }
        }

        val worker = java.io.File(runEntry)
        if (!worker.exists()) {
            return mapOf("error" to "run entry not found: $runEntry")
        }
        return try {
            val processBuilder = ProcessBuilder(nodeExecutable(), runEntry, workflow)
                .directory(worker.parentFile)
            processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
            val child = processBuilder.start()
            val pidKey = "pid:${child.pid()}"
            val secret = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val entry = ActiveRun(child = child, gateIpcSecret = secret)
            activeRuns[pidKey] = entry

            val stdoutThread = Thread { pump(entry, child.inputStream.bufferedReader(), null) }
            val stderrThread = Thread { pump(entry, child.errorStream.bufferedReader(), "[stderr] ") }
            stdoutThread.isDaemon = true
            stderrThread.isDaemon = true
            stdoutThread.start()
            stderrThread.start()

            Thread {
                try {
                    child.waitFor()
                } catch (_: Exception) {
                    // interrupted
                }
                entry.listeners.forEach {
                    try {
                        it.send(RawSseEvent("data: {\"done\":true}\n\n"))
                        it.complete()
                    } catch (_: Exception) {
                        // listener gone
                    }
                }
                entry.listeners.clear()
                entry.child = null
            }.apply { isDaemon = true }.start()

            mapOf("pid" to child.pid())
        } catch (error: Exception) {
            mapOf("error" to (error.message ?: "launch failed"))
        }
    }

    private fun pump(entry: ActiveRun, reader: java.io.BufferedReader, prefix: String?) {
        try {
            reader.forEachLine { line ->
                val text = if (prefix == null) line else "$prefix $line"
                entry.lines.add(text)
                entry.listeners.forEach { listener ->
                    try {
                        listener.send(RawSseEvent("data: ${json(mapOf("line" to text))}\n\n"))
                    } catch (_: Exception) {
                        entry.listeners.remove(listener)
                    }
                }
            }
        } catch (_: Exception) {
            // stream closed
        }
    }

    fun stopRun(runId: String): Map<String, Any?> {
        val entry = activeRuns[runId]
        if (entry == null) {
            val jsonlPath = Path.of(runsDir, "$runId.jsonl")
            if (Files.exists(jsonlPath)) {
                val lines = parseJsonl(jsonlPath.toString())
                if (lines.any { it["kind"] == "run_end" }) {
                    return mapOf("status" to 410, "code" to "RUN_ALREADY_FINISHED", "message" to "Run already finished.")
                }
            }
            return mapOf("status" to 404, "code" to "RUN_NOT_FOUND", "message" to "Run not found.")
        }
        if (entry.child == null) {
            return mapOf("status" to 410, "code" to "RUN_ALREADY_FINISHED", "message" to "Run already finished.")
        }
        if (entry.stopping) {
            return mapOf("status" to 409, "code" to "STOP_ALREADY_REQUESTED", "message" to "Stop already requested.")
        }
        entry.stopping = true
        try {
            entry.child?.destroy()
        } catch (_: Exception) {
            // child may have already exited
        }
        return mapOf("runId" to runId, "stopping" to true)
    }

    // -----------------------------------------------------------------------
    // Review gates
    // -----------------------------------------------------------------------

    fun registerGate(
        runId: String,
        gateInstanceId: String,
        gateType: String,
        findings: Any?,
        outcomes: Any?,
        oracleGate: Any?,
    ): Map<String, Any?> {
        pendingGates[runId] = PendingGate(
            runId = runId,
            gateInstanceId = gateInstanceId,
            gateType = gateType,
            findings = findings,
            outcomes = outcomes,
            oracleGate = oracleGate,
            openedAt = Instant.now().toString(),
        )
        return mapOf("ok" to true)
    }

    fun reviewGate(runId: String): Map<String, Any?> {
        val pending = pendingGates[runId]
        if (pending != null) {
            val response = linkedMapOf<String, Any?>(
                "status" to "pending",
                "gateType" to pending.gateType.ifBlank { "adversarial-review" },
                "gateInstanceId" to pending.gateInstanceId,
                "findings" to pending.findings,
                "outcomes" to pending.outcomes,
                "allowedDecisions" to null,
                "openedAt" to pending.openedAt,
            )
            if (pending.oracleGate != null) response["oracleGate"] = pending.oracleGate
            return response
        }
        val jsonlPath = Path.of(runsDir, "$runId.jsonl")
        if (!Files.exists(jsonlPath)) {
            return mapOf("status" to 404, "code" to "RUN_NOT_FOUND", "message" to "Run not found.")
        }
        val lines = parseJsonl(jsonlPath.toString())
        val runEnd = lines.firstOrNull { it["kind"] == "run_end" }
        var humanDecision: Any? = null
        for (line in lines) {
            if (line["kind"] == "phase_end") {
                val decision = (line["facts"] as? Map<*, *>)?.get("humanDecision")
                if (decision != null) {
                    humanDecision = decision
                    break
                }
            }
        }
        return if (runEnd != null) {
            mapOf(
                "status" to "terminal",
                "humanDecision" to humanDecision,
                "reason" to "Run completed. A terminated process cannot resume. Only future pending gates can receive decisions.",
            )
        } else {
            mapOf("status" to "terminal", "humanDecision" to null, "reason" to "No active review gate for this run.")
        }
    }

    fun replyGate(runId: String, gateInstanceId: String, decision: String, message: String): Map<String, Any?> {
        val pending = pendingGates[runId]
            ?: return mapOf("ok" to false, "status" to 404, "error" to "No active review gate for this run.")
        if (pending.gateInstanceId != gateInstanceId) {
            return mapOf("ok" to false, "status" to 409, "error" to "gateInstanceId does not match the pending gate.")
        }
        pendingGates.remove(runId)
        return mapOf("ok" to true, "decision" to decision, "message" to message)
    }

    // -----------------------------------------------------------------------
    // SSE
    // -----------------------------------------------------------------------

    /** Write the SSE preamble, replay buffered lines and register the listener. */
    fun attachSseStream(runId: String): SseEmitter {
        val emitter = SseEmitter(0L)
        try {
            emitter.send(RawSseEvent(": connected\n\n"))
        } catch (_: Exception) {
            return emitter
        }
        val entry = activeRuns[runId]
        if (entry == null || entry.child == null) {
            try {
                emitter.send(RawSseEvent("data: {\"done\":true}\n\n"))
            } catch (_: Exception) {
                // ignore
            }
            emitter.complete()
            return emitter
        }
        for (line in entry.lines) {
            try {
                emitter.send(RawSseEvent("data: ${json(mapOf("line" to line))}\n\n"))
            } catch (_: Exception) {
                break
            }
        }
        entry.listeners.add(emitter)
        emitter.onCompletion { entry.listeners.remove(emitter) }
        // Complete the emitter on async timeout so the servlet container is not
        // left holding an open async request (which stalls graceful shutdown and
        // surfaces an `AsyncRequestTimeoutException`).
        emitter.onTimeout {
            emitter.complete()
            entry.listeners.remove(emitter)
        }
        emitter.onError { entry.listeners.remove(emitter) }
        return emitter
    }

    /**
     * Completes every live run stream when the context closes.
     *
     * [ContextClosedEvent] is published before Spring Boot's graceful web-server
     * shutdown starts waiting for active requests, so completing the streams here
     * lets the connector drain immediately instead of blocking for the full
     * `spring.lifecycle.timeout-per-shutdown-phase` (30s) and then logging
     * "Graceful shutdown aborted with one or more requests still active".
     */
    override fun onApplicationEvent(event: ContextClosedEvent) {
        for (entry in activeRuns.values) {
            for (listener in entry.listeners.toList()) {
                try {
                    listener.complete()
                } catch (_: Exception) {
                    // already completed or client gone
                }
                entry.listeners.remove(listener)
            }
        }
    }

    private fun json(value: Any?): String = mapper.writeValueAsString(value)

    private fun nodeExecutable(): String = System.getProperty("node.binary") ?: "node"
}
