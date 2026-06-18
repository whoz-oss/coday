package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.metrics.ToolMetricsService
import io.whozoss.agentos.reconciliation.ConfigMergeService
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.tool.ToolResolverService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val toolRegistryService: ToolRegistryService,
    private val toolMetricsService: ToolMetricsService,
) : AgentService {
    override suspend fun findAgentByName(
        namePart: String,
        context: AgentExecutionContext,
    ): Agent {
        val agentConfig =
            agentConfigService.findByName(context.namespaceId, namePart)
                ?: throw IllegalArgumentException(
                    "No AgentConfig found for name '$namePart' in namespace ${context.namespaceId}.",
                )
        val resolvedUser = context.userId?.let { runCatching { userService.findById(it) }.getOrNull() }
        val definition = resolveAgentDefinition(agentConfig, context, resolvedUser)
        return instantiateAgent(definition, context, resolvedUser)
    }

    override suspend fun resolveDefinition(
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
        val resolvedUser = userId?.let { runCatching { userService.findById(it) }.getOrNull() }
        return resolveAgentDefinition(agentConfig, context, resolvedUser)
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
                ).firstOrNull()
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
    private suspend fun resolveAgentDefinition(
        config: AgentConfig,
        context: AgentExecutionContext,
        resolvedUser: User?,
    ): ResolvedAgentDefinition {
        val baseModel =
            config.modelName?.let { aiModelService.findAiModel(context.namespaceId, it) }
                ?: findDefaultModelConfig(context.namespaceId)
                ?: throw IllegalArgumentException(
                    "AgentConfig '${config.name}' could not resolve an AiModel " +
                        "(modelName=${config.modelName}, namespace=${context.namespaceId}).",
                )
        val (modelConfig, providerConfig) =
            applyOverlaysToModel(
                baseModel = baseModel,
                namespaceId = context.namespaceId,
                userId = context.userId,
            )
        val namespaceSystemPrompt =
            buildNamespaceSystemPrompt(namespaceId = context.namespaceId, context = context, resolvedUser = resolvedUser)
        val instructions =
            buildInstructions(
                baseInstructions = config.instructions,
                agentIntegrations = config.integrations,
                context = context,
                resolvedUser = resolvedUser,
            )
        val toolContext =
            context.toToolContext(
                userExternalId = context.userId?.let { userService.findById(it) }?.externalId,
                agentName = config.name,
            )
        val tools =
            if (context.userId != null) {
                toolResolverService.resolveToolsForRun(
                    agentIntegrations = config.integrations,
                    context = toolContext,
                )
            } else {
                toolResolverService.resolveToolsForNamespace(
                    agentIntegrations = config.integrations,
                    context = toolContext,
                )
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
        resolvedUser: User?,
    ): Agent {
        val modelConfig = definition.resolvedModel
        val providerConfig = definition.resolvedProvider

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
                llmProvider = providerConfig.name,
                llmModel = modelConfig.apiModelName,
                toolMetricsService = toolMetricsService,
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
                llmProvider = providerConfig.name,
                llmModel = modelConfig.apiModelName,
            )
        }
    }

    /**
     * Build the namespace context block to be used as a system prompt, separate from
     * the agent's own instructions. Describes the namespace the agent operates in.
     *
     * Each [IntegrationConfig] in the namespace is matched to its [ToolPlugin]. If the
     * plugin implements [io.whozoss.agentos.sdk.tool.ToolPlugin.describeNamespace], its
     * result is appended to the system prompt. Results are collected concurrently;
     * plugins returning null or throwing are silently skipped.
     */
    private suspend fun buildNamespaceSystemPrompt(
        namespaceId: UUID,
        context: AgentExecutionContext,
        resolvedUser: User?,
    ): String {
        val namespace = namespaceService.findById(namespaceId)
        val toolContext =
            ToolContext(
                namespaceId = namespaceId,
                userId = resolvedUser?.id,
                userExternalId = resolvedUser?.externalId,
                caseEvents = emptyList(),
            )
        val integrationDescriptions =
            coroutineScope {
                integrationConfigService
                    .findByParent(namespaceId)
                    .mapNotNull { config -> toolRegistryService.findPlugin(config.integrationType)?.let { config to it } }
                    .map { (config, plugin) ->
                        async {
                            plugin
                                .runCatching { describeNamespace(config.parameters, config.name, toolContext) }
                                .onFailure {
                                    logger.warn(
                                        it,
                                    ) { "describeNamespace failed for '${config.name}' (${config.integrationType})" }
                                }.getOrNull()
                        }
                    }.mapNotNull { it.await() }
                    .filter { it.isNotBlank() }
            }
        return buildString {
            appendLine("## Context: ${namespace?.name ?: namespaceId}")
            namespace?.description?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            integrationDescriptions.forEach { appendLine(it) }
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
     * [agentIntegrations], using the static [IntegrationConfig.description] for each.
     * Configs with no description are excluded. The block is omitted entirely when
     * [agentIntegrations] is null (agent has no declared integrations).
     *
     * The user context block is included when [context.userId] resolves to a known [User]
     * with at least one human-readable field.
     */
    private fun buildInstructions(
        baseInstructions: String?,
        agentIntegrations: Map<String, List<String>?>?,
        context: AgentExecutionContext,
        resolvedUser: User?,
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
            resolvedUser?.let { user ->
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

        return listOfNotNull(baseInstructions.takeUnless { it.isNullOrBlank() }, integrationsBlock, userBlock)
            .joinToString("\n")
    }

    companion object : KLogging()
}
