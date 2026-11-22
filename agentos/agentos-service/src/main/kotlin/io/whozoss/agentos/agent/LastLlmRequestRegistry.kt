package io.whozoss.agentos.agent

import io.whozoss.agentos.chat.LlmRequestOptions
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.content.MediaContent
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The last request an agent sent to its LLM at the end of a turn of a case, exactly as sent, and its answer.
 *
 * A request that appends to [messages] (after an [org.springframework.ai.chat.messages.AssistantMessage] of
 * [finalText]), with the same [requestOptions] and [toolDefinitions] in the same order, has the same prefix: the
 * provider's prompt cache can serve it.
 *
 * Neither the model nor the provider is kept (a provider holds an API key): only their ids, to resolve them again
 * when the request is reused, and the [requestOptions] they produced, to check that they still produce the same.
 *
 * When the turn hit its tool round limit, the request is the one asking the model to summarize without calling any
 * tool: a request reusing it inherits that instruction, which is harmless for a call that has no tool to run.
 */
data class LastLlmRequest(
    val caseId: UUID,
    val namespaceId: UUID,
    /** The user the turn ran for: the provider was resolved with their own overlay, possibly their own API key. */
    val userId: UUID?,
    /** The messages of the request, the system message first, exactly as sent. */
    val messages: List<Message>,
    /** The tools declared by the request, in their order. */
    val toolDefinitions: List<ToolDefinition>,
    /** The model of the request (its reasoning effort may have been the case's instead of its own). */
    val modelId: UUID,
    /** The provider of the model, before the overlay of [userId]. */
    val providerId: UUID,
    val requestOptions: LlmRequestOptions,
    /** The text the model answered to the request. */
    val finalText: String,
    val agentName: String,
    val agentConfigId: UUID?,
    /** How many conversation events ([isConversationEvent]) the case has once the answer is in. */
    val coveredEventCount: Int,
    /** The last conversation event of the case once the answer is in: the answer itself. */
    val lastCoveredEventId: UUID,
    /**
     * The prompt cache choice the request was sent with (that of [userId]). A request meant to read the same cache
     * entry must be sent with it too: with Requesty, Extended rewrites the body
     * ([io.whozoss.agentos.chat.RequestyPromptCache]).
     */
    val promptCacheTtl: PromptCacheTtl = PromptCacheTtl.STANDARD,
    /**
     * When the request was sent: the provider's cache lifetime of its prefix runs from then, not from the end of the
     * answer ([capturedAt], which a long answer can make minutes later). Null when unknown.
     */
    val requestSentAt: Instant? = null,
    /** The tokens a follow-up request re-sends: the request's prompt and its answer, as reported; null when unknown. */
    val prefixTokens: Long? = null,
    /** Set by [LastLlmRequestRegistry.put]. */
    val capturedAt: Instant = Instant.EPOCH,
    /** The generation of the case it was captured in ([LastLlmRequestRegistry.generation]), set by `put`. */
    val generation: Long = 0,
    /** The last time a request reusing this one refreshed the provider's cache ([LastLlmRequestRegistry.touch]). */
    val lastCacheTouchAt: Instant? = null,
) {
    /** When the provider's cache last saw this prefix: when it was sent, or last re-sent ([lastCacheTouchAt]). */
    val lastUsedAt: Instant
        get() {
            val sent = requestSentAt?.takeIf { !it.isAfter(capturedAt) } ?: capturedAt
            return lastCacheTouchAt?.takeIf { it.isAfter(sent) } ?: sent
        }

    /** The conversation of the case is still the one this request ended: nothing was said or done since. */
    fun isCurrentFor(events: List<CaseEvent>): Boolean {
        val conversation = events.filter { it.isConversationEvent() }
        return conversation.size == coveredEventCount && conversation.lastOrNull()?.id == lastCoveredEventId
    }

    /** An approximation of the memory the request holds: characters of text, bytes of media. */
    internal fun estimatedSize(): Long =
        messages.sumOf { message ->
            val text = message.text?.length?.toLong() ?: 0L
            val toolResults = (message as? ToolResponseMessage)?.responses?.sumOf { it.responseData().length.toLong() } ?: 0L
            val toolCalls =
                (message as? AssistantMessage)?.toolCalls?.sumOf { (it.name().length + it.arguments().length).toLong() } ?: 0L
            val media = (message as? MediaContent)?.media?.sumOf { mediaSize(it.data) } ?: 0L
            text + toolResults + toolCalls + media
        } + toolDefinitions.sumOf { (it.description().length + it.inputSchema().length).toLong() } + finalText.length

    private fun mediaSize(data: Any?): Long =
        when (data) {
            is ByteArray -> data.size.toLong()
            is Resource -> runCatching { data.contentLength() }.getOrDefault(0L)
            is String -> data.length.toLong()
            else -> 0L
        }
}

/** The events a turn's messages are built from: what was said, asked, answered, and the tool calls. */
fun CaseEvent.isConversationEvent(): Boolean =
    this is MessageEvent || this is ToolRequestEvent || this is ToolResponseEvent || this is QuestionEvent ||
        this is AnswerEvent

/**
 * What the prompt cache status of a case ([io.whozoss.agentos.chat.PromptCacheStatusService]) needs of its last
 * captured request, kept after the request itself is gone ([LastLlmRequestRegistry.stamp]).
 */
data class PromptCacheStamp(
    /** When the provider's cache last saw the prefix ([LastLlmRequest.lastUsedAt]). */
    val lastUsedAt: Instant,
    val prefixTokens: Long?,
    /** For the model's pricing. */
    val modelId: UUID,
    val apiType: AiApiType,
    val baseUrl: String?,
    val apiModelName: String,
    val promptCacheTtl: PromptCacheTtl,
)

@ConfigurationProperties(prefix = "agentos.llm-request-registry")
data class LlmRequestRegistryProperties(
    /** Approximate memory budget of the registry, in characters of the requests it holds (oldest evicted first). */
    val maxSize: Long = 200L * 1024 * 1024,
)

/**
 * In-memory registry of the [LastLlmRequest] of each case, for the calls that reuse the prompt cache of a
 * conversation (side questions, cache keep-alive). Only the agents that make the request themselves
 * ([AgentSimple]) record one, and only when the exact request is known (a tracked chat model).
 *
 * Bounded: an entry expires [TTL] after its capture or the last refresh of its cache ([touch]), well beyond the
 * default 5-minute prompt cache lifetime (callers decide how old a request they still reuse; the keep-alive bounds
 * how long it refreshes one, [io.whozoss.agentos.caseFlow.CaseKeepAliveProperties.maxIdle]); past [MAX_ENTRIES]
 * cases, or past [LlmRequestRegistryProperties.maxSize], the oldest capture is evicted. Expired entries are also purged every
 * minute. Nothing survives a restart, which is fine: the provider's cache would be cold anyway. Thread-safe.
 *
 * A small [PromptCacheStamp] of each capture outlives it through expiry and eviction, so that a case idle for long
 * can still be shown cold. It is dropped with the request by [invalidate] (a turn that captures nothing must not show
 * the previous one's) and [forget], past [MAX_STAMPS] cases (the oldest use first) or [STAMP_TTL] after its last use
 * (by the periodic purge).
 */
@Component
class LastLlmRequestRegistry(
    private val clock: Clock = Clock.systemUTC(),
    private val properties: LlmRequestRegistryProperties = LlmRequestRegistryProperties(),
) {
    /** In capture order, the oldest first. */
    private val entries = LinkedHashMap<UUID, LastLlmRequest>()
    private var size = 0L

    /** In order of last use, the oldest first. */
    private val stamps = LinkedHashMap<UUID, PromptCacheStamp>()

    /**
     * Bumped for a case by [invalidate]: a capture or a use started before is outdated. Never reset while the case
     * exists; removed when it is deleted ([forget]). One entry per case that ran or was killed since the start of the
     * service, deleted ones aside: a few dozen bytes each, bounded by the cases of the instance.
     */
    private val generations = HashMap<UUID, Long>()

    /** Generations are drawn from one counter, so a returned generation is never 0 (the generation of no entry). */
    private var lastGeneration = 0L

    /**
     * The current generation of [caseId]. Read it when a run starts (to [put] its capture) or with a request
     * about to be reused (to check, right before using it, that nothing moved on since).
     */
    @Synchronized
    fun generation(caseId: UUID): Long = generations[caseId] ?: 0L

    /**
     * The conversation of [caseId] moves on (a run starts) or ends (the case is killed or deleted): its request and its
     * stamp are forgotten, and the captures and uses started before are outdated. Returns the new generation.
     */
    @Synchronized
    fun invalidate(caseId: UUID): Long {
        removeEntry(caseId)
        stamps.remove(caseId)
        return (++lastGeneration).also { generations[caseId] = it }
    }

    /** The last request of [caseId], or null when there is none or it expired. */
    @Synchronized
    fun get(caseId: UUID): LastLlmRequest? {
        val request = entries[caseId] ?: return null
        if (isExpired(request)) {
            removeEntry(caseId)
            return null
        }
        return request
    }

    /**
     * Records [request] as the last one of its case, replacing the previous one, captured now; unless the case is
     * no longer at [generation] (the one read when its run started): then nothing is recorded, and false returned.
     */
    @Synchronized
    fun put(
        request: LastLlmRequest,
        generation: Long,
    ): Boolean {
        if (generation(request.caseId) != generation) return false
        removeEntry(request.caseId)
        val captured = request.copy(capturedAt = clock.instant(), generation = generation, lastCacheTouchAt = null)
        entries[captured.caseId] = captured
        size += captured.estimatedSize()
        putStamp(captured)
        purgeExpiredEntries()
        while (entries.isNotEmpty() && (entries.size > MAX_ENTRIES || size > properties.maxSize)) {
            removeEntry(entries.keys.first())
        }
        return true
    }

    /**
     * A request reusing the one captured at [capturedAt] for [caseId] refreshed the provider's cache, sent at [sentAt]
     * (now by default). Ignored when the case has another request since.
     */
    @Synchronized
    fun touch(
        caseId: UUID,
        capturedAt: Instant,
        sentAt: Instant = clock.instant(),
    ) {
        val request = entries[caseId]?.takeIf { it.capturedAt == capturedAt } ?: return
        entries[caseId] = request.copy(lastCacheTouchAt = sentAt).also(::putStamp)
    }

    /** The [PromptCacheStamp] of the last request captured for [caseId], or null when there is none. */
    @Synchronized
    fun stamp(caseId: UUID): PromptCacheStamp? = stamps[caseId]

    /** The time of the registry's clock. */
    fun now(): Instant = clock.instant()

    /**
     * [caseId] was deleted: its request and its generation are forgotten. A capture still in flight is dropped all
     * the same, its generation (never 0) no longer matching.
     */
    @Synchronized
    fun forget(caseId: UUID) {
        removeEntry(caseId)
        generations.remove(caseId)
        stamps.remove(caseId)
    }

    /** Forgets the request of [caseId], without outdating anything else (see [invalidate]). */
    @Synchronized
    fun remove(caseId: UUID) {
        removeEntry(caseId)
    }

    @Scheduled(fixedDelay = PURGE_INTERVAL_MS, initialDelay = PURGE_INTERVAL_MS)
    @Synchronized
    fun purgeExpired() {
        purgeExpiredEntries()
        val oldestKept = clock.instant().minus(STAMP_TTL)
        stamps.values.removeIf { it.lastUsedAt.isBefore(oldestKept) }
    }

    private fun purgeExpiredEntries() {
        entries.values.filter(::isExpired).map { it.caseId }.forEach(::removeEntry)
    }

    @Synchronized
    internal fun estimatedSize(): Long = size

    private fun putStamp(request: LastLlmRequest) {
        stamps.remove(request.caseId)
        stamps[request.caseId] =
            with(request) {
                PromptCacheStamp(
                    lastUsedAt = lastUsedAt,
                    prefixTokens = prefixTokens,
                    modelId = modelId,
                    apiType = requestOptions.apiType,
                    baseUrl = requestOptions.baseUrl,
                    apiModelName = requestOptions.apiModelName,
                    promptCacheTtl = promptCacheTtl,
                )
            }
        while (stamps.size > MAX_STAMPS) stamps.remove(stamps.keys.first())
    }

    private fun removeEntry(caseId: UUID) {
        entries.remove(caseId)?.let { size -= it.estimatedSize() }
    }

    /** Every request that has not expired, in capture order. */
    @Synchronized
    fun all(): List<LastLlmRequest> {
        purgeExpiredEntries()
        return entries.values.toList()
    }

    /** A refresh of the cache ([touch]: a side question, a keep-alive ping) keeps the request alive. */
    private fun isExpired(request: LastLlmRequest): Boolean = request.lastUsedAt.plus(TTL).isBefore(clock.instant())

    companion object {
        /** How long an entry lives after its capture or the last refresh of its cache ([touch]). */
        val TTL: Duration = Duration.ofMinutes(65)
        const val MAX_ENTRIES = 500

        /** How long a [PromptCacheStamp] is kept after its last use, and how many cases keep one. */
        val STAMP_TTL: Duration = Duration.ofDays(7)
        const val MAX_STAMPS = 10_000
        private const val PURGE_INTERVAL_MS = 60_000L
    }
}
