package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The last request an agent sent to its LLM at the end of a turn of a case, exactly as sent, and its answer.
 *
 * A request that appends to [messages] (after an [org.springframework.ai.chat.messages.AssistantMessage] of
 * [finalText]), with the same [model], [provider] and [toolDefinitions] in the same order, has the same prefix:
 * the provider's prompt cache can serve it.
 */
data class LastLlmRequest(
    val caseId: UUID,
    /** The messages of the request, the system message first, exactly as sent. */
    val messages: List<Message>,
    /** The tools declared by the request, in their order. */
    val toolDefinitions: List<ToolDefinition>,
    /** The model of the request, with its inference options (temperature, reasoning effort...). */
    val model: AiModel,
    /** The provider of the request, with its headers. */
    val provider: AiProvider,
    /** The answer of the turn, as recorded in the case (the text of all its rounds). */
    val finalText: String,
    val agentName: String,
    val agentConfigId: UUID?,
    /** How many events of the case the turn was run with. */
    val coveredEventCount: Int,
    /** The last event of the case the turn was run with: later ones are not in [messages]. */
    val lastCoveredEventId: UUID?,
    val capturedAt: Instant = Instant.now(),
)

/**
 * In-memory registry of the [LastLlmRequest] of each case, for the calls that reuse the prompt cache of a
 * conversation (side questions, cache keep-alive). Only the agents that make the request themselves
 * ([AgentSimple]) record one.
 *
 * Bounded: an entry expires [TTL] after its capture (a bit more than the longest prompt cache lifetime), and
 * past [MAX_ENTRIES] cases the oldest capture is evicted. Nothing survives a restart, which is fine: the
 * provider's cache would be cold anyway after such a delay. Thread-safe.
 */
@Component
class LastLlmRequestRegistry(
    private val clock: Clock = Clock.systemUTC(),
) {
    /** In capture order, the oldest first. */
    private val entries = LinkedHashMap<UUID, LastLlmRequest>()

    /** The last request of [caseId], or null when there is none or it expired. */
    @Synchronized
    fun get(caseId: UUID): LastLlmRequest? {
        val request = entries[caseId] ?: return null
        if (isExpired(request)) {
            entries.remove(caseId)
            return null
        }
        return request
    }

    /** Records [request] as the last one of its case, replacing the previous one. */
    @Synchronized
    fun put(request: LastLlmRequest) {
        entries.remove(request.caseId)
        entries[request.caseId] = request
        entries.values.removeIf(::isExpired)
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    @Synchronized
    fun remove(caseId: UUID) {
        entries.remove(caseId)
    }

    private fun isExpired(request: LastLlmRequest): Boolean = request.capturedAt.plus(TTL).isBefore(clock.instant())

    companion object {
        /** A bit more than the 1-hour prompt cache lifetime of the providers. */
        val TTL: Duration = Duration.ofMinutes(65)
        const val MAX_ENTRIES = 500
    }
}
