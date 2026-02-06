@file:Suppress("ktlint:standard:no-wildcard-imports")

package io.whozoss.agentos.agent

import io.whozoss.agentos.aiProvider.ChatClientProvider
import io.whozoss.agentos.aiProvider.ModelConfig
import io.whozoss.agentos.orchestration.AgentSimple
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*

/**
 * Implementation of AgentService with hard-coded agent definitions.
 *
 * For now, creates agents from simple hard-coded configurations.
 * Future: May load from AgentRegistry, YAML files, or plugin system.
 */
@Service
class AgentServiceImpl(
    private val chatClientProvider: ChatClientProvider,
    @Value("\${agentos.default-provider:anthropic}") private val defaultProviderId: String,
) : AgentService {
    private val logger = LoggerFactory.getLogger(AgentServiceImpl::class.java)

    // ========================================
    // Hard-coded Agent Definitions
    // ========================================

    private val hardCodedAgents =
        listOf(
            AiModel(
                metadata = EntityMetadata(id = UUID.nameUUIDFromBytes("general-purpose".toByteArray())),
                name = "AgentOS General Assistant",
                description = "Default agent running on AgentOS - Kotlin/Spring AI orchestration layer",
                instructions =
                    """
                    You are an AI assistant running on AgentOS, a Kotlin-based orchestration system powered by Spring AI.
                    
                    AgentOS is a proof-of-concept that demonstrates:
                    - Agent orchestration with Kotlin coroutines
                    - Multi-turn conversations with context management
                    - Integration with Coday's TypeScript frontend via REST/SSE
                    - Streaming responses for real-time user experience
                    
                    When you introduce yourself or are asked about your environment, mention that you're running on AgentOS (Kotlin/Spring Boot backend) 
                    and communicating with Coday's web interface. Be enthusiastic about this POC!
                    
                    For specialized tasks, you can suggest delegating to other agents when the plugin system is fully implemented.
                    
                    Be helpful, clear, and occasionally mention the cool tech stack you're running on! 🚀
                    """.trimIndent(),
                provider = null, // Will use default provider from configuration
            ),
        )

    // ========================================
    // Agent Discovery & Runtime Instance Creation
    // ========================================

    override fun findAgentByName(namePart: String): Agent {
        // Find agent model from hard-coded definitions
        val matchingAgents =
            hardCodedAgents.filter {
                it.name.contains(namePart, ignoreCase = true)
            }

        val agentModel =
            when {
                matchingAgents.isEmpty() -> {
                    throw IllegalArgumentException("Agent not found: $namePart")
                }

                matchingAgents.size == 1 -> {
                    matchingAgents.first()
                }

                else -> {
                    // Try exact match first
                    val exactMatch =
                        matchingAgents.find {
                            it.name.equals(namePart, ignoreCase = true)
                        }
                    exactMatch ?: throw IllegalArgumentException(
                        "Ambiguous agent name: $namePart. Matches: ${matchingAgents.map { it.name }}",
                    )
                }
            }

        logger.info("Found agent: ${agentModel.name} (${agentModel.id})")

        // Create runtime instance
        return createAgentInstance(agentModel)
    }

    override fun listAgents(): List<Agent> = hardCodedAgents.map { createAgentInstance(it) }

    override fun getDefaultAgent(): Agent? {
        // Return the first hard-coded agent as default
        val defaultModel = hardCodedAgents.firstOrNull() ?: return null

        logger.info("Using default agent: ${defaultModel.name}")
        return createAgentInstance(defaultModel)
    }

    /**
     * Create a runtime agent instance from an AgentModel.
     * For now, creates AgentSimple instances.
     * Future: Could use factory pattern based on agent configuration.
     */
    private fun createAgentInstance(model: AiModel): Agent {
        logger.info("[AgentService] Creating agent instance for: ${model.name}")

        // TODO: Load tools based on agent model
        val tools = emptyList<StandardTool<*>>()

        // POC: Use the injected ChatModel (auto-configured by Spring AI)
        logger.info("[AgentService] Creating ChatClient from injected ChatModel")
        val chatClient =
            chatClientProvider.getChatClient(
                ModelConfig(
                    providerName = model.name,
                    apiKey = model.provider!!.defaultApiKey,
                    model = model.modelName,
                ),
            )
        logger.info("[AgentService] ChatClient created successfully")

        return AgentSimple(
            metadata = EntityMetadata(id = model.id),
            model = model,
            chatClient = chatClient,
            tools = tools,
        )
    }

    override suspend fun cleanup() {
        logger.info("Cleaning up agent resources")
        // TODO: Cleanup any cached agent instances or resources
    }

    override suspend fun kill() {
        logger.info("Killing all agent operations")
        // TODO: Force stop any running agent operations
    }
}
