package io.biznet.agentos.orchestration

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Implementation of IAgentService with hard-coded agent definitions.
 *
 * For now, creates agents from simple hard-coded configurations.
 * Future: May load from AgentRegistry, YAML files, or plugin system.
 */
@Service
class AgentService(
    private val orchestratorChatClientProvider: OrchestratorChatClientProvider,
) : IAgentService {
    private val logger = LoggerFactory.getLogger(AgentService::class.java)

    // ========================================
    // Hard-coded Agent Definitions
    // ========================================

    private val hardCodedAgents =
        listOf(
            AgentModel(
                id = UUID.nameUUIDFromBytes("general-purpose".toByteArray()),
                name = "General Purpose Agent",
                description = "Default agent without any particular purpose, should delegate specific tasks to other agents",
                instructions = "You are a helpful AI assistant. When specialized tasks are needed, suggest delegating to appropriate specialized agents.",
                provider = null, // Will use default provider from configuration
            ),
        )

    // ========================================
    // Agent Discovery & Runtime Instance Creation
    // ========================================

    override fun findAgentByName(namePart: String): IAgent {
        // Find agent model from hard-coded definitions
        val matchingAgents =
            hardCodedAgents.filter {
                it.name.contains(namePart, ignoreCase = true)
            }

        val agentModel =
            when {
                matchingAgents.isEmpty() -> throw IllegalArgumentException("Agent not found: $namePart")
                matchingAgents.size == 1 -> matchingAgents.first()
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

    override fun getDefaultAgent(): IAgent? {
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
    private fun createAgentInstance(model: AgentModel): IAgent {
        // TODO: Load tools based on agent model
        val tools = emptyList<io.biznet.agentos.tools.domain.StandardTool<*>>()

        val chatClient = orchestratorChatClientProvider.getChatClient()

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
