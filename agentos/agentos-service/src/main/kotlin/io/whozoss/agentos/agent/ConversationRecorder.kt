package io.whozoss.agentos.agent

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import mu.KLogging
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

/**
 * A single message in the LLM chat history, serialized in a format that is
 * independent of any Kotlin or Spring AI class — ready to be consumed by a
 * fine-tuning pipeline without any knowledge of the AgentOS codebase.
 *
 * [role] is one of: "system", "user", "assistant", "tool".
 * [content] is the text content of the message.
 * [toolName] is only present on role="assistant" tool-call messages and role="tool" response messages.
 * [toolCallId] pairs each tool-call assistant message with its tool response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RecordedMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolCallId: String? = null,
)

/**
 * One annotation record, written as a single JSON line to the annotation file.
 *
 * Each record corresponds to exactly one LLM call. Multiple lines will be written
 * per agent run (one per LLM call: intentions, parameter generations, final response).
 * The human annotator selects the relevant lines manually.
 *
 * Training example reconstruction:
 *   messages = record["history"]   // already the complete LLM input
 *   target   = record["rawOutput"]
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConversationRecord(
    val caseId: UUID,
    val namespaceId: UUID,
    val agentName: String,
    val timestamp: Instant,
    val callType: String,
    val toolName: String?,
    val history: List<RecordedMessage>,
    val rawOutput: String,
    val success: Boolean = true,
)

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * ANNOTATION MODE — not committed, local only.
 *
 * Writes one JSON line to [outputFile] for every LLM call recorded via [appendTurn].
 * Lines are always appended — never replaced. The human annotator selects the
 * relevant lines manually after the run.
 *
 * [startConversation] registers the run metadata (namespaceId, agentName) so it
 * is available when [appendTurn] writes each line. [flush] cleans up that
 * in-memory state at the end of the run.
 *
 * Thread-safety: each caseId gets its own metadata entry; concurrent runs for
 * different cases are isolated.
 */
@Service
class ConversationRecorder(
    private val objectMapper: ObjectMapper =
        ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
) {
    private data class RunMeta(
        val namespaceId: UUID,
        val agentName: String,
        val timestamp: Instant,
    )

    private val runs = ConcurrentHashMap<UUID, RunMeta>()

    /** Output file — one JSON object per line (JSONL). */
    val outputFile: File = run {
        val default = File(System.getProperty("user.dir")).resolve("../../annotation-data/annotation-data.jsonl")
        File(System.getProperty("annotation.output", default.canonicalPath)).also { it.parentFile?.mkdirs() }
    }

    /**
     * Registers run metadata for [caseId]. Safe to call multiple times —
     * subsequent calls for the same caseId are no-ops.
     */
    fun startConversation(
        caseId: UUID,
        namespaceId: UUID,
        agentName: String,
    ) {
        runs.computeIfAbsent(caseId) {
            RunMeta(
                namespaceId = namespaceId,
                agentName = agentName,
                timestamp = Instant.now(),
            )
        }
    }

    /**
     * Immediately writes one [ConversationRecord] line to [outputFile] for this
     * LLM call. Every call produces its own line — the annotator selects the
     * relevant ones manually.
     */
    fun appendTurn(
        caseId: UUID,
        callType: String,
        toolName: String?,
        history: List<RecordedMessage>,
        rawOutput: String,
        success: Boolean = true,
    ) {
        val meta = runs[caseId]
        if (meta == null) {
            logger.warn { "[ConversationRecorder] appendTurn called for unknown caseId=$caseId — was startConversation called?" }
            return
        }
        runCatching {
            val record = ConversationRecord(
                caseId = caseId,
                namespaceId = meta.namespaceId,
                agentName = meta.agentName,
                timestamp = meta.timestamp,
                callType = callType,
                toolName = toolName,
                history = history,
                rawOutput = rawOutput,
                success = success,
            )
            val line = objectMapper.writeValueAsString(record)
            outputFile.appendText(line + "\n")
            logger.info { "[ConversationRecorder] wrote callType=$callType for caseId=$caseId to ${outputFile.absolutePath}" }
        }.onFailure {
            logger.error(it) { "[ConversationRecorder] failed to write record for caseId=$caseId" }
        }
    }

    /**
     * Removes the run metadata for [caseId] from memory. No-op if the caseId
     * is unknown (e.g. no turns were recorded for this run).
     */
    fun flush(caseId: UUID) {
        runs.remove(caseId)
            ?: logger.debug { "[ConversationRecorder] flush called for unknown caseId=$caseId — no turns were recorded" }
    }

    companion object : KLogging()
}
