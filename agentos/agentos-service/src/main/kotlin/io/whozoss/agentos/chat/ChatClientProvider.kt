package io.whozoss.agentos.chat

import io.whozoss.agentos.compaction.ContextBudget
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import org.springframework.ai.chat.client.ChatClient
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
 * Agent execution is recognised by the [ContextBudget] it passes to [getChatClient]. Its model is then
 * wrapped in [UsageTrackingChatModel], whatever the usage switch says: AgentOS owns the tool loop (and so
 * the cap on tool rounds) and observes the context of every request. Only the cost part depends on the
 * switch: when usage is enabled and an accumulator is given, every request also goes through accounting
 * and the cost gate. Clients outside agent execution (case naming, prompt translation, utility calls) pass
 * no budget and keep their existing behaviour: no wrapper, unless usage is enabled and they hand over an
 * accumulator.
 *
 * The cap on tool rounds is the caller's [getChatClient] `maxToolRounds` (the namespace's override)
 * when given, else the platform's `agentos.limits.agent-max-iterations`.
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
        contextBudget: ContextBudget? = null,
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
            )
        val costAccumulator = accumulator.takeIf { usageConfig.enabled }
        val trackedModel =
            if (contextBudget != null || costAccumulator != null) {
                UsageTrackingChatModel(
                    chatModel,
                    costAccumulator,
                    providerConfig.apiType,
                    modelConfig,
                    maxToolRounds = maxToolRounds ?: limits.agentMaxIterations,
                    contextBudget = contextBudget ?: ContextBudget.disabled(),
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
