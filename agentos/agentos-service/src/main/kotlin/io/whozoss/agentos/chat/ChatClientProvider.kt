package io.whozoss.agentos.chat

import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service

/**
 * Creates [ChatClient] instances on demand from a resolved [AiModel] + [AiProvider] pair.
 *
 * The two entities carry everything [ChatModelFactory] needs:
 * - provider connectivity ([AiProvider.apiType], [AiProvider.baseUrl], [AiProvider.apiKey])
 * - model identity and inference parameters ([AiModel.apiModelName],
 *   [AiModel.temperature], [AiModel.maxCompletionTokens], [AiModel.reasoningEffort])
 *
 * Resolution of which model/provider pair to use is the responsibility of the caller
 * (currently [io.whozoss.agentos.agent.AgentServiceImpl]).
 *
 * When usage is enabled, tracking is applied to the model so every call/stream terminal and internal tool
 * round goes through accounting and the cost gate. Clients outside agent execution
 * (no accumulator) retain their existing behaviour.
 *
 * The cap on tool rounds is the caller's [getChatClient] `maxToolRounds` (the namespace's override)
 * when given, else the platform's `agentos.limits.agent-max-iterations`.
 *
 * With `runTools` false, the client never executes a tool, whatever the model answers: the model always goes
 * through [UsageTrackingChatModel], which returns a response asking for tools as it is (and turns Spring AI's
 * internal tool execution off). Without an accumulator to record into (usage disabled), its usage is discarded.
 *
 * `onRequest` sees the prompt of every request sent to the provider, tool rounds included. It is only called
 * through [UsageTrackingChatModel] (usage tracked, or `runTools` false): an untracked model runs its tool rounds
 * out of sight.
 *
 * The prompt cache duration ([getChatClient] `promptCacheTtl`) is required so every caller decides it: agent
 * runs pass the duration chosen by the user they run for, utility calls (case naming, summary, prompt
 * translation) pass [PromptCacheTtl.FIVE_MINUTES]. It only changes the request for Claude models via Requesty.
 */
@Service
class ChatClientProvider(
    private val chatModelFactory: ChatModelFactory,
    private val limits: LimitsConfigProperties = LimitsConfigProperties(),
    private val usageConfig: UsageConfigProperties = UsageConfigProperties(),
) {
    fun getChatClient(
        modelConfig: AiModel,
        providerConfig: AiProvider,
        caseId: String? = null,
        accumulator: UsageAccumulator? = null,
        maxToolRounds: Int? = null,
        promptCacheTtl: PromptCacheTtl,
        runTools: Boolean = true,
        onRequest: ((Prompt) -> Unit)? = null,
    ): ChatClient {
        val chatModel =
            chatModelFactory.createChatModel(
                apiType = providerConfig.apiType,
                baseUrl = providerConfig.baseUrl,
                apiKey = providerConfig.apiKey,
                modelName = modelConfig.apiModelName,
                temperature = modelConfig.temperature,
                maxCompletionTokens = modelConfig.maxCompletionTokens,
                headers = providerConfig.headers + (caseId?.let { mapOf(X_SESSION_ID to it) } ?: emptyMap()),
                reasoningEffort = modelConfig.reasoningEffort,
                promptCacheTtl = promptCacheTtl,
            )
        val tracked = usageConfig.enabled && accumulator != null
        val trackedModel =
            if (tracked || !runTools) {
                UsageTrackingChatModel(
                    chatModel,
                    accumulator?.takeIf { tracked } ?: UsageAccumulator(),
                    providerConfig.apiType,
                    modelConfig,
                    maxToolRounds = maxToolRounds ?: limits.agentMaxIterations,
                    runTools = runTools,
                    onRequest = onRequest,
                )
            } else {
                chatModel
            }
        return ChatClient.builder(trackedModel).build()
    }

    companion object {
        const val X_SESSION_ID = "X-Session-Id"
    }
}

/**
 * The model for short utility calls (case naming, prompt translation): its reasoning effort is capped
 * at [ReasoningEffort.LOW], so these calls neither pay for the effort configured for agents nor fall
 * back to a provider default above a level the admin chose to save cost. Returns the model itself
 * when its effort is null, NONE or LOW.
 */
internal fun AiModel.forUtilityCall(): AiModel {
    val effort = reasoningEffort
    return if (effort == null || effort <= ReasoningEffort.LOW) this else copy(reasoningEffort = ReasoningEffort.LOW)
}
