package io.whozoss.agentos.agent

import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.orchestration.AgentSimple
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistry
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Implementation of [AgentService] that builds runtime agents from registered [AiModel]s.
 *
 * AiModels are discovered from plugins via [AiModelRegistry].
 * Each model references an AiProvider by name for API connectivity.
 *
 * At instantiation time, the [AgentExecutionContext] is used to:
 * - Resolve the namespace description and append it to the agent's system instructions,
 *   so the agent always knows which namespace it is operating in regardless of how long
 *   the conversation grows (system prompt is never compacted by the provider).
 * - (Future) scope tool resolution to the namespace and user.
 */
@Service
class AgentServiceImpl(
    private val chatClientProvider: ChatClientProvider,
    private val toolRegistry: ToolRegistry,
    private val aiModelRegistry: AiModelRegistry,
    private val namespaceService: NamespaceService,
) : AgentService {
    override fun findAgentByName(
        namePart: String,
        context: AgentExecutionContext,
    ): Agent {
        val model =
            aiModelRegistry.findByName(namePart)
                ?: aiModelRegistry.getAll().firstOrNull { it.name.contains(namePart, ignoreCase = true) }
                ?: throw IllegalArgumentException("Agent not found: $namePart")

        logger.info { "Found agent model: ${model.name}" }
        return createAgentInstance(model, context)
    }

    override fun listAgents(): List<Agent> = aiModelRegistry.getAll().map { createAgentInstance(it, context = null) }

    override fun getDefaultAgent(context: AgentExecutionContext): Agent? {
        val model = aiModelRegistry.getDefault() ?: return null
        logger.info { "Using default agent: ${model.name}" }
        return createAgentInstance(model, context)
    }

    override fun getDefaultAgentName(): String? = aiModelRegistry.getDefault()?.name

    override fun resolveAgentName(namePart: String): String? =
        (
            aiModelRegistry.findByName(namePart)
                ?: aiModelRegistry.getAll().firstOrNull { it.name.contains(namePart, ignoreCase = true) }
        )?.name

    /**
     * Build a live [AgentSimple] instance from [model].
     *
     * When [context] is provided the namespace description is appended to the model's
     * system instructions so the LLM always receives it in the privileged system-prompt
     * channel (Anthropic `system`, OpenAI `system` role) rather than in the message
     * history where it could be compacted away.
     *
     * When [context] is null (e.g. [listAgents] for registry inspection) the model's
     * instructions are used as-is.
     */
    private fun createAgentInstance(
        model: AiModel,
        context: AgentExecutionContext?,
    ): Agent {
        logger.info { "[AgentService] Creating agent instance for: ${model.name}, context: $context" }

        val tools = toolRegistry.listTools()
        logger.info {
            "[AgentService] Loaded ${tools.size} tool(s) " +
                "(sample-5 : ${tools.take(5).map { it.name }}) " +
                "for agent: ${model.name}"
        }

        val chatClient = chatClientProvider.getChatClient(model.name)
        logger.debug { "[AgentService] ChatClient created for model: ${model.name} via provider: ${model.providerName}" }

        val instructions = buildInstructions(model, context)

        return AgentSimple(
            metadata = EntityMetadata(id = UUID.nameUUIDFromBytes(model.name.toByteArray())),
            model = model.copy(instructions = instructions),
            chatClient = chatClient,
            tools = tools,
        )
    }

    /**
     * Compose the final system instructions for the agent.
     *
     * Starts from the model's own instructions (may be null) and appends the namespace
     * context block when a [context] is available and the namespace has a description.
     * The namespace name is always included so the agent knows where it is operating
     * even if no description has been written yet.
     */
    private fun buildInstructions(
        model: AiModel,
        context: AgentExecutionContext?,
    ): String? =
        context?.let {
            val namespace = namespaceService.findById(it.namespaceId)
            val namespaceBlock =
                buildString {
                    appendLine()
                    appendLine("## Context: ${namespace?.name ?: it.namespaceId}")
                    if (!namespace?.description.isNullOrBlank()) {
                        appendLine(namespace!!.description!!)
                    }
                }.trimEnd()

            if (model.instructions.isNullOrBlank()) namespaceBlock else "${model.instructions}\n$namespaceBlock"
        } ?: model.instructions

    companion object : KLogging()
}
