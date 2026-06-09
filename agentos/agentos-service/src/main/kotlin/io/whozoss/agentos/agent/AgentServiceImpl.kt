package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.reconciliation.ConfigMergeService
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.tool.ToolResolverService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Implementation of [AgentService] that resolves agents from namespace-scoped
 * [AgentConfig] entities.
 *
 */
@Service
class AgentServiceImpl(
    private val chatClientProvider: ChatClientProvider,
    private val toolResolverService: ToolResolverService,
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val namespaceService: NamespaceService,
    private val integrationConfigService: IntegrationConfigService,
    private val userService: UserService,
    private val aiProviderReconciliationService: ConfigMergeService<AiProvider>,
    private val agentConfigService: AgentConfigService,
    private val intentionGenerator: AgentIntentionGenerator,
    private val confirmationManager: ConfirmationManager,
    private val objectMapper: ObjectMapper,
) : AgentService {
    override fun findAgentByName(
        namePart: String,
        context: AgentExecutionContext,
    ): Agent {
        val agentConfig =
            agentConfigService.findByName(context.namespaceId, namePart)
                ?: throw IllegalArgumentException(
                    "No AgentConfig found for name '$namePart' in namespace ${context.namespaceId}.",
                )
        val definition = resolveAgentDefinition(agentConfig, context)
        return instantiateAgent(definition, context)
    }

    override fun resolveDefinition(
        agentConfigId: UUID,
        namespaceId: UUID,
        userId: UUID?,
    ): ResolvedAgentDefinition {
        val agentConfig =
            agentConfigService.findById(agentConfigId)
                ?: throw IllegalArgumentException(
                    "No AgentConfig found for id '$agentConfigId' in namespace $namespaceId.",
                )
        val context =
            AgentExecutionContext(
                namespaceId = namespaceId,
                userId = userId,
            )
        return resolveAgentDefinition(agentConfig, context)
    }

    override fun resolveAgentName(
        namePart: String,
        namespaceId: UUID,
        userId: UUID?,
    ): String? =
        if (userId != null) {
            agentConfigService
                .findAvailableByNamespaceIdAndUserId(
                    namespaceId = namespaceId,
                    userId = userId,
                    agentName = namePart,
                )
                .firstOrNull()
                ?.name
        } else {
            agentConfigService.findByName(namespaceId, namePart)?.name
        }

    // -------------------------------------------------------------------------
    // Resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Phase 1: resolve all configuration for an [AgentConfig] into a [ResolvedAgentDefinition].
     *
     * Performs model / provider overlay resolution, instruction building, and tool resolution.
     * Does not touch the Spring AI chat client or instantiate any agent object.
     *
     * Throws [IllegalArgumentException] if no model can be resolved.
     */
    private fun resolveAgentDefinition(
        config: AgentConfig,
        context: AgentExecutionContext,
    ): ResolvedAgentDefinition {
        val baseModel =
            config.modelName?.let { aiModelService.findAiModel(context.namespaceId, it) }
                ?: findDefaultModelConfig(context.namespaceId)
                ?: throw IllegalArgumentException(
                    "AgentConfig '${config.name}' could not resolve an AiModel " +
                        "(modelName=${config.modelName}, namespace=${context.namespaceId}).",
                )
        val (modelConfig, providerConfig) = applyOverlaysToModel(baseModel, context.namespaceId, context.userId)
        val namespaceSystemPrompt = buildNamespaceSystemPrompt(context.namespaceId)
        val instructions = buildInstructions(
            baseInstructions = config.instructions,
            agentIntegrations = config.integrations,
            context = context,
        )
        val tools =
            if (context.userId != null) {
                toolResolverService.resolveToolsForRun(context.namespaceId, context.userId, config.integrations, config.name)
            } else {
                toolResolverService.resolveToolsForNamespace(context.namespaceId, config.integrations, config.name)
            }
        return ResolvedAgentDefinition(
            agentConfigId = config.metadata.id,
            name = config.name,
            systemPrompt = namespaceSystemPrompt.takeUnless { it.isBlank() },
            instructions = instructions.takeUnless { it.isBlank() },
            resolvedModelApiName = modelConfig.apiModelName,
            resolvedProviderName = providerConfig.name,
            resolvedModelId = modelConfig.metadata.id,
            resolvedProviderId = providerConfig.metadata.id,
            resolvedModel = modelConfig,
            resolvedProvider = providerConfig,
            tools = tools,
            advancedExecution = config.advancedExecution,
            namespaceId = context.namespaceId,
            userId = context.userId,
        )
    }

    /**
     * Phase 2: instantiate a live [Agent] from a [ResolvedAgentDefinition].
     *
     * Constructs the Spring AI chat client and the appropriate agent type
     * ([AgentSimple] or [AgentAdvanced]). Requires the original [context] for
     * the case-scoped providers that cannot be captured in the definition.
     */
    private fun instantiateAgent(
        definition: ResolvedAgentDefinition,
        context: AgentExecutionContext,
    ): Agent {
        val modelConfig = definition.resolvedModel
        val providerConfig = definition.resolvedProvider
        val resolvedUser = context.userId?.let { runCatching { userService.findById(it) }.getOrNull() }
        return createAgentInstance(
            agentName = definition.name,
            resolvedInstructions = definition.instructions,
            resolvedSystemPrompt = definition.systemPrompt,
            advancedExecution = definition.advancedExecution,
            modelConfig = modelConfig,
            providerConfig = providerConfig,
            context = context,
            resolvedTools = definition.tools,
            resolvedUser = resolvedUser,
        )
    }

    /**
     * Resolve the [AiProvider] for a pre-resolved [baseModel]. When [userId] is non-null,
     * applies 3-tier reconciliation on the provider. When null, falls back to direct
     * repository lookup — preserves Epic 4 behaviour exactly.
     */
    private fun applyOverlaysToModel(
        baseModel: AiModel,
        namespaceId: UUID,
        userId: UUID?,
    ): Pair<AiModel, AiProvider> {
        val baseProvider = aiProviderService.getById(baseModel.aiProviderId)
        val providerConfig =
            if (userId != null) {
                aiProviderReconciliationService.resolve(namespaceId, userId, baseProvider.name)
            } else {
                baseProvider
            }

        logger.info {
            "Resolved model '${baseModel.alias ?: baseModel.apiModelName}' " +
                "-> apiName='${baseModel.apiModelName}' (priority=${baseModel.priority}, " +
                "provider='${providerConfig.name}', userId=$userId)"
        }
        return baseModel to providerConfig
    }

    private fun findDefaultModelConfig(namespaceId: UUID): AiModel? = aiModelService.findAiModel(namespaceId)

    // -------------------------------------------------------------------------
    // Agent instantiation
    // -------------------------------------------------------------------------

    /**
     * Build a live [AgentSimple] or [AgentAdvanced] instance from fully-resolved inputs.
     *
     * All parameters carry already-resolved values — this method only constructs
     * the Spring AI chat client and the appropriate agent type.
     * [resolvedInstructions] are the final system instructions (built by [resolveAgentDefinition]).
     * [resolvedTools] is the pre-resolved and pre-filtered tool set.
     * [resolvedUser] is the pre-resolved user (may be null for anonymous / system runs).
     */
    private fun createAgentInstance(
        agentName: String,
        resolvedInstructions: String?,
        resolvedSystemPrompt: String?,
        advancedExecution: Boolean,
        modelConfig: AiModel,
        providerConfig: AiProvider,
        context: AgentExecutionContext,
        resolvedTools: Collection<StandardTool<*>>,
        resolvedUser: User?,
    ): Agent {
        logger.info { "Creating agent '$agentName' for namespace ${context.namespaceId} (userId=${context.userId})" }
        logger.info { "Loaded ${resolvedTools.size} tool(s) for agent '$agentName'" }
        logger.debug { "Tools for '$agentName': ${resolvedTools.map { it.name }}" }
        logger.trace { "Tools detail for '$agentName':\n" + resolvedTools.joinToString("\n") { "  - ${it.name}: ${it.description}" } }
        logger.trace { "Final instructions for '$agentName':\n$resolvedInstructions" }

        val chatClient = chatClientProvider.getChatClient(modelConfig, providerConfig)

        return if (advancedExecution) {
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val advancedContext =
                AgentAdvancedContext(
                    chatClient = chatClient,
                    tools = resolvedTools.toList(),
                    instructions = resolvedInstructions,
                    agentId = agentId,
                    confirmationManager = confirmationManager,
                    systemPrompt = resolvedSystemPrompt,
                )
            AgentAdvanced(
                metadata = EntityMetadata(id = agentId),
                name = agentName,
                context = advancedContext,
                intentionGenerator = intentionGenerator,
                objectMapper = objectMapper,
                userId = resolvedUser?.metadata?.id,
                userExternalId = resolvedUser?.externalId,
                caseEventsProvider = context.caseEventsProvider,
            )
        } else {
            AgentSimple(
                metadata = EntityMetadata(id = UUID.nameUUIDFromBytes(agentName.toByteArray())),
                name = agentName,
                chatClient = chatClient,
                tools = resolvedTools,
                systemPrompt = resolvedSystemPrompt,
                instructions = resolvedInstructions,
                userId = resolvedUser?.metadata?.id,
                userExternalId = resolvedUser?.externalId,
                caseEventsProvider = context.caseEventsProvider,
            )
        }
    }

    /**
     * Build the namespace context block to be used as a system prompt, separate from
     * the agent's own instructions. Describes the namespace the agent operates in.
     */
    private fun buildNamespaceSystemPrompt(namespaceId: UUID): String {
        val namespace = namespaceService.findById(namespaceId)
        return buildString {
            appendLine("## Context: ${namespace?.name ?: namespaceId}")
            namespace?.description?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        }.trimEnd()
    }

    /**
     * Compose the agent's instructions from [baseInstructions] (the agent's own instructions
     * from [AgentConfig]), an integrations block, and a user context block.
     *
     * The namespace context is intentionally NOT part of this — it is built separately
     * by [buildNamespaceSystemPrompt] and sent as a system prompt.
     *
     * The integrations block lists [IntegrationConfig] entries whose
     * [name][io.whozoss.agentos.integrationConfig.IntegrationConfig.name] appears in
     * [agentIntegrations] AND that carry a non-null description. It is omitted entirely
     * when [agentIntegrations] is null (agent has no declared integrations).
     *
     * The user context block is included when [context.userId] resolves to a known [User]
     * with at least one human-readable field.
     */
    private fun buildInstructions(
        baseInstructions: String?,
        agentIntegrations: Map<String, List<String>?>?,
        context: AgentExecutionContext,
    ): String {
        val integrationsBlock =
            when {
                agentIntegrations == null -> {
                    null
                }

                else -> {
                    val listed =
                        integrationConfigService
                            .findByParent(context.namespaceId)
                            .filter { it.name in agentIntegrations && !it.description.isNullOrBlank() }
                    when {
                        listed.isEmpty() -> {
                            null
                        }

                        else -> {
                            buildString {
                                appendLine()
                                appendLine("## Integrations")
                                listed.forEach { config ->
                                    appendLine("- ${config.name}: ${config.description}")
                                }
                            }.trimEnd()
                        }
                    }
                }
            }

        val userBlock =
            context.userId?.let { userId ->
                userService.findById(userId)?.let { user ->
                    // Only inject a user block when at least one human-readable field is present.
                    // Internal UUIDs (id, externalId) are intentionally excluded — they carry no
                    // conversational meaning and confuse the LLM about who the interlocutor is.
                    val hasIdentity = !user.firstname.isNullOrBlank() || !user.lastname.isNullOrBlank()
                    val hasContext = !user.bio.isNullOrBlank() || user.email.isNotBlank()
                    if (!hasIdentity && !hasContext) {
                        null
                    } else {
                        buildString {
                            appendLine()
                            appendLine("## User")
                            if (!user.firstname.isNullOrBlank()) appendLine("- firstname: ${user.firstname}")
                            if (!user.lastname.isNullOrBlank()) appendLine("- lastname: ${user.lastname}")
                            if (user.email.isNotBlank()) appendLine("- email: ${user.email}")
                            if (!user.bio.isNullOrBlank()) appendLine("- bio: ${user.bio}")
                        }.trimEnd()
                    }
                }
            }

        return listOfNotNull(baseInstructions.takeUnless { it.isNullOrBlank() }, integrationsBlock, userBlock)
            .joinToString("\n")
    }

    companion object : KLogging()
}
