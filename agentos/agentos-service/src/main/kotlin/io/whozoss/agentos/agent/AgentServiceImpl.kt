@file:Suppress("ktlint:standard:no-wildcard-imports")

package io.whozoss.agentos.agent

import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.orchestration.AgentSimple
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistry
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.*

/**
 * Implementation of AgentService that builds runtime agents from registered AiModels.
 *
 * AiModels are discovered from plugins via AiModelRegistry.
 * Each model references an AiProvider by name for API connectivity.
 */
@Service
class AgentServiceImpl(
    private val chatClientProvider: ChatClientProvider,
    private val toolRegistry: ToolRegistry,
    private val aiModelRegistry: AiModelRegistry,
) : AgentService {
    override fun findAgentByName(namePart: String): Agent {
        val model =
            aiModelRegistry.findByName(namePart)
                ?: aiModelRegistry.getAll().firstOrNull { it.name.contains(namePart, ignoreCase = true) }
                ?: throw IllegalArgumentException("Agent not found: $namePart")

        logger.info { "Found agent model: ${model.name}" }
        return createAgentInstance(model)
    }

    override fun listAgents(): List<Agent> = aiModelRegistry.getAll().map { createAgentInstance(it) }

    override fun getDefaultAgent(): Agent? {
        val model = aiModelRegistry.getDefault() ?: return null
        logger.info { "Using default agent: ${model.name}" }
        return createAgentInstance(model)
    }

    private fun createAgentInstance(model: AiModel): Agent {
        logger.info { "[AgentService] Creating agent instance for: ${model.name}" }

        val tools = toolRegistry.listTools()
        logger.info { "[AgentService] Loaded ${tools.size} tool(s) for agent: ${model.name}" }

        val chatClient = chatClientProvider.getChatClient(model.modelName)
        logger.info { "[AgentService] ChatClient created for model: ${model.name} via provider: ${model.providerName}" }

        return AgentSimple(
            metadata = EntityMetadata(id = UUID.nameUUIDFromBytes(model.name.toByteArray())),
            model = model,
            chatClient = chatClient,
            tools = tools,
        )
    }

    override suspend fun cleanup() {
        logger.info { "Cleaning up agent resources" }
    }

    override suspend fun kill() {
        logger.info { "Killing all agent operations" }
    }

    companion object : KLogging()
}
