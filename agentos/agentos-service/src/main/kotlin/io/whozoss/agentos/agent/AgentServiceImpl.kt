package io.whozoss.agentos.agent

import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.user.UserService
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
 * - Scope tool resolution to the namespace via [ToolRegistryService.resolveToolsForNamespace],
 *   producing fresh tool instances for each agent run.
 */
@Service
class AgentServiceImpl(
    private val chatClientProvider: ChatClientProvider,
    private val toolRegistryService: ToolRegistryService,
    private val aiModelRegistry: AiModelRegistry,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
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
     * When [context] is provided:
     * - The namespace description is appended to the model's system instructions.
     * - Fresh tool instances are resolved for the namespace via [ToolRegistryService].
     *
     * When [context] is null (e.g. [listAgents] for registry inspection) the model's
     * instructions are used as-is and no tools are loaded — the agent is not meant to run.
     */
    private fun createAgentInstance(
        model: AiModel,
        context: AgentExecutionContext?,
    ): Agent {
        logger.info { "[AgentService] Creating agent instance for: ${model.name}, context: $context" }

        val tools = if (context != null) {
            val resolved = toolRegistryService.resolveToolsForNamespace(context.namespaceId)
            logger.info {
                "[AgentService] Loaded ${resolved.size} tool(s) " +
                    "(sample-5 : ${resolved.take(5).map { it.name }}) " +
                    "for agent: ${model.name}"
            }
            resolved
        } else {
            emptyList()
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
     * Starts from the model's own instructions (may be null) and appends:
     * 1. A namespace context block (always, when [context] is provided).
     * 2. A user context block (when [context.userId] resolves to a known [User]).
     *
     * Both blocks are injected in the privileged system-prompt channel so they are
     * never compacted away by the provider, regardless of conversation length.
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
                    appendLine("""## Context: ${namespace?.name ?: it.namespaceId}""")
                    if (!namespace?.description.isNullOrBlank()) {
                        appendLine(namespace!!.description!!)
                    }
                }.trimEnd()

            val userBlock =
                it.userId?.let { userId ->
                    userService.findById(userId)?.let { user ->
                        buildString {
                            appendLine()
                            appendLine("## User")
                            appendLine("- id: ${user.metadata.id}")
                            appendLine("- email: ${user.email}")
                            if (!user.firstname.isNullOrBlank()) appendLine("- firstname: ${user.firstname}")
                            if (!user.lastname.isNullOrBlank()) appendLine("- lastname: ${user.lastname}")
                            if (!user.bio.isNullOrBlank()) appendLine("- bio: ${user.bio}")
                        }.trimEnd()
                    }
                }

            val base = if (model.instructions.isNullOrBlank()) namespaceBlock else "${model.instructions}\n$namespaceBlock"
            if (userBlock != null) "$base\n$userBlock" else base
        } ?: model.instructions

    companion object : KLogging()
}
