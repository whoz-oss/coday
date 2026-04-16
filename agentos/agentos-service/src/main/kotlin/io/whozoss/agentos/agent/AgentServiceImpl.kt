package io.whozoss.agentos.agent

import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
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
 * - Append an integration-descriptions block to the system prompt listing any
 *   [IntegrationConfig][io.whozoss.agentos.integrationConfig.IntegrationConfig]s in the
 *   namespace that carry a non-null description, so the agent understands what each
 *   named integration is for.
 *
 * Every agent-instantiating method requires a non-null [AgentExecutionContext].
 * Name-resolution methods ([getDefaultAgentName], [resolveAgentName]) are context-free.
 */
@Service
class AgentServiceImpl(
    private val chatClientProvider: ChatClientProvider,
    private val aiModelRegistry: AiModelRegistry,
    private val toolRegistryService: ToolRegistryService,
    private val namespaceService: NamespaceService,
    private val integrationConfigService: IntegrationConfigService,
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
     * Build a live [AgentSimple] instance from [model], scoped to [context].
     *
     * - The namespace description is appended to the model's system instructions.
     * - Fresh tool instances are resolved for the namespace via [ToolRegistryService].
     */
    private fun createAgentInstance(
        model: AiModel,
        context: AgentExecutionContext,
    ): Agent {
        logger.info { "[AgentService] Creating agent instance for: ${model.name}, context: $context" }

        val resolved = toolRegistryService.resolveToolsForNamespace(context.namespaceId)
        logger.info {
            "[AgentService] Loaded ${resolved.size} tool(s) " +
                "(sample-5 : ${resolved.take(5).map { it.name }}) " +
                "for agent: ${model.name}"
        }

        val chatClient = chatClientProvider.getChatClient(model.name)
        logger.debug { "[AgentService] ChatClient created for model: ${model.name} via provider: ${model.providerName}" }

        val instructions = buildInstructions(model, context)

        return AgentSimple(
            metadata = EntityMetadata(id = UUID.nameUUIDFromBytes(model.name.toByteArray())),
            model = model.copy(instructions = instructions),
            chatClient = chatClient,
            tools = resolved,
        )
    }

    /**
     * Compose the final system instructions for the agent.
     *
     * Starts from the model's own instructions (may be null) and appends:
     * 1. A namespace context block (always, when [context] is provided).
     * 2. An integrations block listing each [IntegrationConfig] in the namespace that
     *    carries a non-null description (omitted entirely when none have a description).
     * 3. A user context block (when [context.userId] resolves to a known [User]).
     *
     * All blocks are injected in the privileged system-prompt channel so they are
     * never compacted away by the provider, regardless of conversation length.
     */
    private fun buildInstructions(
        model: AiModel,
        context: AgentExecutionContext,
    ): String {
        val namespace = namespaceService.findById(context.namespaceId)
        val namespaceBlock =
            buildString {
                appendLine()
                appendLine("""## Context: ${namespace?.name ?: context.namespaceId}""")
                namespace?.description?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            }.trimEnd()

        val integrationsWithDescription = integrationConfigService
            .findByParent(context.namespaceId)
            .filter { !it.description.isNullOrBlank() }
        val integrationsBlock = when {
            integrationsWithDescription.isEmpty() -> null
            else -> buildString {
                appendLine()
                appendLine("## Integrations")
                integrationsWithDescription.forEach { config ->
                    appendLine("- ${config.name}: ${config.description}")
                }
            }.trimEnd()
        }

        val userBlock =
            context.userId?.let { userId ->
                userService.findById(userId)?.let { user ->
                    buildString {
                        appendLine()
                        appendLine("## User")
                        appendLine("- id: ${user.metadata.id}")
                        if (user.email.isNotBlank()) appendLine("- email: ${user.email}")
                            if (!user.firstname.isNullOrBlank()) appendLine("- firstname: ${user.firstname}")
                            if (!user.lastname.isNullOrBlank()) appendLine("- lastname: ${user.lastname}")
                            if (!user.bio.isNullOrBlank()) appendLine("- bio: ${user.bio}")
                        }.trimEnd()
                    }
                }

        val base = if (model.instructions.isNullOrBlank()) namespaceBlock else "${model.instructions}\n$namespaceBlock"
        val withIntegrations = if (integrationsBlock != null) "$base\n$integrationsBlock" else base
        return if (userBlock != null) "$withIntegrations\n$userBlock" else withIntegrations
    }

    companion object : KLogging()
}
