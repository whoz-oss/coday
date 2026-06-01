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
import io.whozoss.agentos.tool.ToolResolverService
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
        return createAgentFromConfig(agentConfig, context)
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
     * Build an [Agent] from an [AgentConfig].
     *
     * The config's [AgentConfig.modelName] is used to resolve the [AiModel] (alias
     * first, then apiName). When [AgentConfig.modelName] is null, the namespace
     * default model is used as a fallback.
     *
     * Throws [IllegalArgumentException] if no model can be resolved.
     */
    private fun createAgentFromConfig(
        config: AgentConfig,
        context: AgentExecutionContext,
    ): Agent {
        val baseModel =
            config.modelName?.let { aiModelService.findAiModel(context.namespaceId, it) }
                ?: findDefaultModelConfig(context.namespaceId)
                ?: throw IllegalArgumentException(
                    "AgentConfig '${config.name}' could not resolve an AiModel " +
                        "(modelName=${config.modelName}, namespace=${context.namespaceId}).",
                )
        val (modelConfig, providerConfig) = applyOverlaysToModel(baseModel, context.namespaceId, context.userId)
        return createAgentInstance(
            config.name,
            config.instructions,
            config.integrations,
            config.advancedExecution,
            modelConfig,
            providerConfig,
            context,
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
     * Build a live [AgentSimple] or [AgentAdvanced] instance from the resolved entity pair, scoped to [context].
     *
     * [agentName] is the logical name used to identify this agent.
     * [baseInstructions] are the agent-level instructions from [AgentConfig], if any.
     * [agentIntegrations] is the optional tool-access filter from [AgentConfig.integrations].
     * The namespace description, integrations, and user context are always appended.
     *
     * When [context.userId] is non-null, uses [ToolResolverService.resolveToolsForRun] to apply
     * 3-tier tool reconciliation while still honoring [agentIntegrations]. Falls back to
     * [ToolResolverService.resolveToolsForNamespace] for anonymous/system runs
     * (userId == null, legacy path, AC11).
     */
    private fun createAgentInstance(
        agentName: String,
        baseInstructions: String?,
        agentIntegrations: Map<String, List<String>?>?,
        advancedExecution: Boolean,
        modelConfig: AiModel,
        providerConfig: AiProvider,
        context: AgentExecutionContext,
    ): Agent {
        logger.info { "Creating agent '$agentName' for namespace ${context.namespaceId} (userId=${context.userId})" }

        val tools =
            if (context.userId != null) {
                toolResolverService.resolveToolsForRun(context.namespaceId, context.userId, agentIntegrations)
            } else {
                toolResolverService.resolveToolsForNamespace(context.namespaceId, agentIntegrations)
            }
        logger.info { "Loaded ${tools.size} tool(s) for agent '$agentName'" }
        logger.debug { "Tools for '$agentName': ${tools.map { it.name }}" }
        logger.trace { "Tools detail for '$agentName':\n" + tools.joinToString("\n") { "  - ${it.name}: ${it.description}" } }

        // Resolve user identity once here so plugins receive it via ToolContext without
        // needing access to UserService themselves.
        val resolvedUser = context.userId?.let { runCatching { userService.findById(it) }.getOrNull() }

        val chatClient = chatClientProvider.getChatClient(modelConfig, providerConfig)
        val instructions = buildInstructions(baseInstructions = baseInstructions, agentIntegrations = agentIntegrations, context = context)
        logger.trace { "Final instructions for '$agentName':\n$instructions" }

        return if (advancedExecution) {
            val agentId = UUID.nameUUIDFromBytes(agentName.toByteArray())
            val advancedContext =
                AgentAdvancedContext(
                    chatClient = chatClient,
                    tools = tools.toList(),
                    instructions = instructions,
                    agentId = agentId,
                    confirmationManager = confirmationManager,
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
                tools = tools,
                instructions = instructions,
                userId = resolvedUser?.metadata?.id,
                userExternalId = resolvedUser?.externalId,
                caseEventsProvider = context.caseEventsProvider,
            )
        }
    }

    /**
     * Compose the final system instructions for the agent.
     *
     * Starts from [baseInstructions] (the agent's own instructions from [AgentConfig],
     * may be null) and appends:
     * 1. A namespace context block (always, when [context] is provided).
     * 2. An integrations block listing the [IntegrationConfig] entries whose [name][io.whozoss.agentos.integrationConfig.IntegrationConfig.name]
     *    appears in [agentIntegrations] AND that carry a non-null description. When
     *    [agentIntegrations] is null (agent has no declared integrations), this block
     *    is omitted entirely — the agent has no tools and should not be told about
     *    integrations it cannot use.
     * 3. A user context block (when [context.userId] resolves to a known [User]).
     *
     * All blocks are injected in the privileged system-prompt channel so they are
     * never compacted away by the provider, regardless of conversation length.
     */
    private fun buildInstructions(
        baseInstructions: String?,
        agentIntegrations: Map<String, List<String>?>?,
        context: AgentExecutionContext,
    ): String {
        val namespace = namespaceService.findById(context.namespaceId)
        val namespaceBlock =
            buildString {
                appendLine()
                appendLine("""## Context: ${namespace?.name ?: context.namespaceId}""")
                namespace?.description?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            }.trimEnd()

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

        return listOfNotNull(baseInstructions.takeUnless { it.isNullOrBlank() }, namespaceBlock, integrationsBlock, userBlock)
            .joinToString("\n")
    }

    companion object : KLogging()
}
