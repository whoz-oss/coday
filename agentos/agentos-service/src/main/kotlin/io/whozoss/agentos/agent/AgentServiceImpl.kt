package io.whozoss.agentos.agent

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.reconciliation.ConfigReconciliationService
import io.whozoss.agentos.reconciliation.RunReconciliationCache
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Implementation of [AgentService] that resolves agents from namespace-scoped
 * [AgentConfig] entities.
 *
 * ## Resolution strategy for [findAgentByName]
 *
 * Look up an [AgentConfig] in the namespace whose `name` matches [namePart]
 * (case-insensitive).
 * - If found: use the config's `name` as agent identity, its `instructions` as
 *   base system prompt, and resolve the model via `config.modelName`
 *   (alias-first, then apiName).
 * - If not found: throws [IllegalArgumentException].
 *
 * ## Resolution strategy for [getDefaultAgent] / [getDefaultAgentName]
 *
 * Delegates to [AgentConfigService.findDefault], which always returns a non-null
 * [AgentConfig] — either the oldest persisted config in the namespace, or the
 * built-in fallback. [getDefaultAgentName] is therefore always non-null.
 *
 * [getDefaultAgent] can still return null when the resolved config has no
 * [AgentConfig.modelName] and no [AiModel] is configured for the namespace.
 */
@Service
class AgentServiceImpl(
    private val chatClientProvider: ChatClientProvider,
    private val toolRegistryService: ToolRegistryService,
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val namespaceService: NamespaceService,
    private val integrationConfigService: IntegrationConfigService,
    private val userService: UserService,
    private val aiModelReconciliationService: ConfigReconciliationService<AiModel>,
    private val aiProviderReconciliationService: ConfigReconciliationService<AiProvider>,
    private val agentConfigService: AgentConfigService,
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

    override fun getDefaultAgent(context: AgentExecutionContext): Agent? {
        val config = agentConfigService.findDefault(context.namespaceId)
            .let { if (it.namespaceId == BOGUS_NAMESPACE_ID) it.copy(namespaceId = context.namespaceId) else it }
        return runCatching { createAgentFromConfig(config, context) }
            .onFailure { logger.warn { "[AgentService] Cannot instantiate default agent for namespace ${context.namespaceId}: ${it.message}" } }
            .getOrNull()
    }

    override fun getDefaultAgentName(namespaceId: UUID): String = agentConfigService.findDefault(namespaceId).name

    override fun resolveAgentName(
        namePart: String,
        namespaceId: UUID,
    ): String? = agentConfigService.findByName(namespaceId, namePart)?.name

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
        val cache = if (context.userId != null) RunReconciliationCache() else null
        val modelLookupName = config.modelName
            ?: findDefaultModelConfig(context.namespaceId)
                ?.let { it.alias ?: it.apiModelName }
            ?: throw IllegalArgumentException(
                "AgentConfig '${config.name}' has no modelName and no default AiModel is configured " +
                    "for namespace ${context.namespaceId}.",
            )
        val (modelConfig, providerConfig) =
            resolveModelPair(modelLookupName, context.namespaceId, context.userId, cache)
        return createAgentInstance(config.name, config.instructions, config.integrations, modelConfig, providerConfig, context, cache)
    }

    /**
     * Resolve a [AiModel] + [AiProvider] pair for [name] within [namespaceId].
     *
     * When [userId] is non-null, applies 3-tier reconciliation:
     * - model: resolved via [aiModelReconciliationService] with the alias (or apiModelName) as key
     * - provider: resolved via [aiProviderReconciliationService] with the provider name as key
     *
     * When [userId] is null (legacy path), falls back to direct repository lookup without
     * any user overlay — preserves Epic 4 behavior exactly (NFR-INT-1, AC11).
     */
    private fun resolveModelPair(
        name: String,
        namespaceId: UUID,
        userId: UUID? = null,
        cache: RunReconciliationCache? = null,
    ): Pair<AiModel, AiProvider> {
        logger.debug { "[AgentService] Resolving '$name' in namespace $namespaceId (userId=$userId)" }

        val baseModel =
            aiModelService.findAiModel(namespaceId, name)
                ?: throw IllegalArgumentException(
                    "No AiModel found for name '$name' in namespace $namespaceId. " +
                        "Configure an AiModel with alias or apiName matching '$name'.",
                )

        val modelConfig: AiModel
        val providerConfig: AiProvider

        if (userId != null) {
            val reconciliationName = baseModel.alias ?: baseModel.apiModelName
            modelConfig = cache?.getOrCompute(reconciliationName, AiModel::class.java) {
                aiModelReconciliationService.resolve(namespaceId, userId, reconciliationName)
            } ?: aiModelReconciliationService.resolve(namespaceId, userId, reconciliationName)

            val baseProvider = aiProviderService.getById(modelConfig.aiProviderId)
            providerConfig = cache?.getOrCompute(baseProvider.name, AiProvider::class.java) {
                aiProviderReconciliationService.resolve(namespaceId, userId, baseProvider.name)
            } ?: aiProviderReconciliationService.resolve(namespaceId, userId, baseProvider.name)
        } else {
            modelConfig = baseModel
            providerConfig = aiProviderService.getById(baseModel.aiProviderId)
        }

        logger.info {
            "[AgentService] Resolved '$name' -> apiName='${modelConfig.apiModelName}' " +
                "(alias=${modelConfig.alias}, priority=${modelConfig.priority}, provider='${providerConfig.name}')"
        }
        return modelConfig to providerConfig
    }

    private fun findDefaultModelConfig(namespaceId: UUID): AiModel? = aiModelService.findAiModel(namespaceId)

    // -------------------------------------------------------------------------
    // Agent instantiation
    // -------------------------------------------------------------------------

    /**
     * Build a live [AgentSimple] instance from the resolved entity pair, scoped to [context].
     *
     * [agentName] is the logical name used to identify this agent.
     * [baseInstructions] are the agent-level instructions from [AgentConfig], if any.
     * [agentIntegrations] is the optional tool-access filter from [AgentConfig.integrations].
     * The namespace description, integrations, and user context are always appended.
     *
     * When [context.userId] is non-null, uses [ToolRegistryService.resolveToolsForRun] to apply
     * 3-tier tool reconciliation while still honoring [agentIntegrations]. Falls back to
     * [ToolRegistryService.resolveToolsForNamespace] for anonymous/system runs
     * (userId == null, legacy path, AC11).
     */
    private fun createAgentInstance(
        agentName: String,
        baseInstructions: String?,
        agentIntegrations: Map<String, List<String>?>?,
        modelConfig: AiModel,
        providerConfig: AiProvider,
        context: AgentExecutionContext,
        cache: RunReconciliationCache? = null,
    ): Agent {
        logger.info { "[AgentService] Creating agent '$agentName' for namespace ${context.namespaceId} (userId=${context.userId})" }

        val tools =
            if (context.userId != null) {
                toolRegistryService.resolveToolsForRun(context.namespaceId, context.userId, cache, agentIntegrations)
            } else {
                toolRegistryService.resolveToolsForNamespace(context.namespaceId, agentIntegrations)
            }
        logger.info {
            "[AgentService] Loaded ${tools.size} tool(s) " +
                "(sample-5: ${tools.take(5).map { it.name }}) for agent: $agentName"
        }

        val chatClient = chatClientProvider.getChatClient(modelConfig, providerConfig)
        val instructions = buildInstructions(baseInstructions = baseInstructions, agentIntegrations = agentIntegrations, context = context)

        return AgentSimple(
            metadata = EntityMetadata(id = UUID.nameUUIDFromBytes(agentName.toByteArray())),
            name = agentName,
            chatClient = chatClient,
            tools = tools,
            instructions = instructions,
        )
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
                agentIntegrations == null -> null
                else -> {
                    val listed =
                        integrationConfigService
                            .findByParent(context.namespaceId)
                            .filter { it.name in agentIntegrations && !it.description.isNullOrBlank() }
                    when {
                        listed.isEmpty() -> null
                        else ->
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

        return listOfNotNull(baseInstructions.takeUnless { it.isNullOrBlank() }, namespaceBlock, integrationsBlock, userBlock)
            .joinToString("\n")
    }

    companion object : KLogging() {
        /** Sentinel namespace UUID emitted by [AgentConfigServiceImpl.DEFAULT_AGENT_CONFIG]. */
        private val BOGUS_NAMESPACE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
