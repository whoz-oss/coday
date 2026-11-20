package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.DefinitionOnlyToolCallback
import io.whozoss.agentos.chat.LlmRequestOptions
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.RunCostService
import io.whozoss.agentos.usage.RunCostService.NonPausingRegistration
import io.whozoss.agentos.usage.UsageOutcome
import io.whozoss.agentos.usage.UsageRecord
import io.whozoss.agentos.usage.UsageRecordService
import io.whozoss.agentos.usage.UsageSource
import mu.KLogging
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.stereotype.Service

/**
 * Sends one keep-alive ping of a case's prompt cache: the agent's last request ([LastLlmRequest]) again, as it was
 * sent, with an answer limited to one token.
 *
 * What makes the provider read the very cache entry the next turn will read, and so refresh its lifetime:
 * - the same messages, in the same order, nothing appended (a hidden "reply ok" message would refresh another
 *   entry than the one the next real message reads);
 * - the same tool definitions in the same order, as definitions only ([DefinitionOnlyToolCallback]): no tool can
 *   run, and the client never executes one either (`runTools = false`);
 * - the same model and provider, resolved again by id ([resolve]; a provider holds an API key, the snapshot does
 *   not) and only used when they still produce the same [LlmRequestOptions], with the same prompt cache duration,
 *   so that the Requesty one-hour rewrite ([io.whozoss.agentos.chat.RequestyPromptCache]) marks the body the same
 *   way.
 *
 * Only the output limit differs: `max_tokens: 1` (the parameter Requesty documents, which Claude models take)
 * instead of the model's `max_completion_tokens`, which is left out of this request; OpenAI reasoning models
 * ([isOpenAiReasoningModel]) reject `max_tokens`, so they get `max_completion_tokens: 1`. The output limit is not part
 * of the cached prefix. Only OpenAI-compatible providers ([supports]) are pinged: they are the ones whose 1-token
 * request is checked on the wire.
 *
 * The call is blocking (`call()`, no stream). Nothing is written in the case and nothing is broadcast; the usage is
 * recorded on the case ([UsageSource.KEEP_ALIVE]), for the user whose run made the request, and counts toward its run
 * cost. Its cost admission is [RunCostService.registerNonPausing], without opening a cost window of its own: refused
 * when the case or an ancestor has ended, was stopped, waits for a confirmation or is at its threshold.
 */
@Service
class CaseKeepAlivePinger(
    private val chatClientProvider: ChatClientProvider,
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val usageRecordService: UsageRecordService,
    private val runCostService: RunCostService,
    private val usageConfig: UsageConfigProperties = UsageConfigProperties(),
) {
    /** What a [ping] did. */
    sealed interface Result {
        /** Sent; [usage] is what the provider reported. */
        data class Sent(
            val usage: LlmUsage,
        ) : Result

        /** Not sent: the cost guard refused it ([reason]). */
        data class CostRefused(
            val reason: NonPausingRegistration.Reason,
        ) : Result

        /** Not sent: the model or provider no longer produces the request that was cached. */
        object OptionsChanged : Result
    }

    /**
     * The model and provider of [request], resolved again as the agent did (the case's reasoning effort, the
     * overlay of the user), or null when they no longer produce the same request options.
     */
    fun resolve(
        case: Case,
        request: LastLlmRequest,
    ): Pair<AiModel, AiProvider>? {
        val baseModel = aiModelService.findById(request.modelId) ?: return null
        val model = case.reasoningEffort?.let { baseModel.copy(reasoningEffort = it) } ?: baseModel
        val baseProvider = runCatching { aiProviderService.getById(request.providerId) }.getOrNull() ?: return null
        val userId = request.userId
        val provider =
            if (userId == null) {
                baseProvider
            } else {
                runCatching { aiProviderService.resolveProvider(request.namespaceId, userId, baseProvider.name) }.getOrNull()
            } ?: return null
        return (model to provider).takeIf { LlmRequestOptions.of(model, provider) == request.requestOptions }
    }

    /** Sends the ping of [request] for [case]; throws when the provider fails. */
    fun ping(
        case: Case,
        request: LastLlmRequest,
    ): Result {
        val (model, provider) = resolve(case, request) ?: return Result.OptionsChanged
        val accumulator = UsageAccumulator()
        val registration =
            if (usageConfig.enabled) {
                when (val admission = runCostService.registerNonPausing(case.id, accumulator, openSessions = false)) {
                    is NonPausingRegistration.Registered -> admission.registration
                    is NonPausingRegistration.Refused -> return Result.CostRefused(admission.reason)
                }
            } else {
                null
            }
        var outcome = UsageOutcome.FAILED
        try {
            val spec =
                chatClientProvider
                    .getChatClient(
                        pingModel(model, provider.apiType),
                        provider,
                        case.id.toString(),
                        accumulator,
                        promptCacheTtl = request.promptCacheTtl,
                        runTools = false,
                    ).prompt(Prompt(request.messages))
                    .options(oneTokenOptions(model, provider.apiType))
            val withTools =
                if (request.toolDefinitions.isEmpty()) {
                    spec
                } else {
                    spec.toolCallbacks(request.toolDefinitions.map(::DefinitionOnlyToolCallback))
                }
            withTools.call().chatResponse()
            outcome = UsageOutcome.COMPLETED
            return Result.Sent(accumulator.total)
        } finally {
            val persist = { persistUsage(case, request, model, provider, accumulator, outcome) }
            if (registration != null) registration.finish(persist) else persist()
        }
    }

    private fun persistUsage(
        case: Case,
        request: LastLlmRequest,
        model: AiModel,
        provider: AiProvider,
        accumulator: UsageAccumulator,
        outcome: UsageOutcome,
    ) {
        if (!usageConfig.enabled || !accumulator.hasData) return
        runCatching {
            accumulator.recordGroups().forEach { group ->
                usageRecordService.create(
                    UsageRecord.fromLlmUsage(
                        llmUsage = group,
                        namespaceId = case.namespaceId,
                        caseId = case.id,
                        agentName = request.agentName,
                        outcome = outcome,
                        userId = request.userId,
                        agentConfigId = request.agentConfigId,
                        providerName = provider.name,
                        apiModelName = model.apiModelName,
                        source = UsageSource.KEEP_ALIVE,
                    ),
                )
            }
        }.onFailure { e -> logger.error(e) { "[UsageRecord] Failed to write the usage of a keep-alive ping on case ${case.id}" } }
    }

    companion object : KLogging() {
        const val MAX_OUTPUT_TOKENS = 1

        /** OpenAI reasoning models (o1, o3, o4-mini, gpt-5...), with or without a router prefix (`openai/o3`). */
        private val OPENAI_REASONING_MODEL = Regex("(?i)(^|/)(o\\d|gpt-5)")

        /** Whether a request to a provider of [apiType] can be pinged: OpenAI-compatible APIs only. */
        fun supports(apiType: AiApiType): Boolean = apiType == AiApiType.OpenAI || apiType == AiApiType.vLLM

        /** OpenAI reasoning models reject `max_tokens` and only take `max_completion_tokens`. */
        fun isOpenAiReasoningModel(apiModelName: String): Boolean = OPENAI_REASONING_MODEL.containsMatchIn(apiModelName)

        /**
         * The model of the ping: the model of the request, without the output limit the OpenAI API type sends as
         * `max_completion_tokens` (the ping sends its own limit, and a body must not carry two). The vLLM API type
         * already sends its limit as `max_tokens`, which the ping's replaces.
         */
        internal fun pingModel(
            model: AiModel,
            apiType: AiApiType,
        ): AiModel = if (apiType == AiApiType.OpenAI) model.copy(maxCompletionTokens = null) else model

        /** The 1-token limit, under the parameter the model takes. */
        internal fun oneTokenOptions(
            model: AiModel,
            apiType: AiApiType,
        ): OpenAiChatOptions =
            if (apiType == AiApiType.OpenAI && isOpenAiReasoningModel(model.apiModelName)) {
                OpenAiChatOptions.builder().maxCompletionTokens(MAX_OUTPUT_TOKENS).build()
            } else {
                OpenAiChatOptions.builder().maxTokens(MAX_OUTPUT_TOKENS).build()
            }
    }
}
