package io.whozoss.agentos.agent

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
 * [AiModel] + [AiProvider] entity pairs.
 *
 * Resolution strategy for a logical model name (e.g. "default"):
 * 1. Load all [AiModel] entries for the namespace.
 * 2. Match by [AiModel.alias] first, then [AiModel.apiModelName] as fallback.
 *    Within each group, the config with the highest [AiModel.priority] wins.
 * 3. Load the parent [AiProvider] to get provider connectivity (apiType, baseUrl, apiKey).
 * 4. Build a [ChatClient] directly from the two entities via [ChatClientProvider].
 *
 * There is no fallback to a plugin-based registry — if no matching [AiModel]
 * exists for the namespace the call fails fast with a clear error.
 * - Append an integration-descriptions block to the system prompt listing any
 *   [IntegrationConfig][io.whozoss.agentos.integrationConfig.IntegrationConfig]s in the
 *   namespace that carry a non-null description, so the agent understands what each
 *   named integration is for.
 *
 * The recommended alias for the primary model in a namespace is "default". This keeps
 * agent definitions provider-agnostic: switching providers only requires updating the
 * [AiModel], not any agent definition.
 *
 * Agent definitions are currently hardcoded and reference a logical model name.
 * Once an Agent entity is introduced this service will resolve against that instead.
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
) : AgentService {
    override fun findAgentByName(
        namePart: String,
        context: AgentExecutionContext,
    ): Agent {
        val cache = if (context.userId != null) RunReconciliationCache() else null
        val (modelConfig, providerConfig) = resolveModelPair(namePart, context.namespaceId, context.userId, cache)
        val canonicalName = modelConfig.alias ?: modelConfig.apiModelName
        return createAgentInstance(canonicalName, modelConfig, providerConfig, context, cache)
    }

    override fun getDefaultAgent(context: AgentExecutionContext): Agent? {
        val baseModel = findDefaultModelConfig(context.namespaceId) ?: return null
        val defaultName = baseModel.alias ?: baseModel.apiModelName
        val cache = if (context.userId != null) RunReconciliationCache() else null
        val (modelConfig, providerConfig) = resolveModelPair(defaultName, context.namespaceId, context.userId, cache)
        return createAgentInstance(modelConfig.alias ?: modelConfig.apiModelName, modelConfig, providerConfig, context, cache)
    }

    override fun getDefaultAgentName(namespaceId: UUID): String? {
        val modelConfig = findDefaultModelConfig(namespaceId) ?: return null
        return modelConfig.alias ?: modelConfig.apiModelName
    }

    override fun resolveAgentName(
        namePart: String,
        namespaceId: UUID,
    ): String? = aiModelService.findAiModel(namespaceId, namePart)?.let { it.alias ?: it.apiModelName }

    // -------------------------------------------------------------------------
    // Resolution helpers
    // -------------------------------------------------------------------------

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
     * When [context.userId] is non-null, uses [ToolRegistryService.resolveToolsForRun] to apply
     * 3-tier tool reconciliation. Falls back to [ToolRegistryService.resolveToolsForNamespace]
     * for anonymous/system runs (userId == null, legacy path, AC11).
     */
    private fun createAgentInstance(
        agentName: String,
        modelConfig: AiModel,
        providerConfig: AiProvider,
        context: AgentExecutionContext,
        cache: RunReconciliationCache? = null,
    ): Agent {
        logger.info { "[AgentService] Creating agent '$agentName' for namespace ${context.namespaceId} (userId=${context.userId})" }

        val tools =
            if (context.userId != null) {
                toolRegistryService.resolveToolsForRun(context.namespaceId, context.userId, cache)
            } else {
                toolRegistryService.resolveToolsForNamespace(context.namespaceId)
            }
        logger.info {
            "[AgentService] Loaded ${tools.size} tool(s) " +
                "(sample-5: ${tools.take(5).map { it.name }}) for agent: $agentName"
        }

        val chatClient = chatClientProvider.getChatClient(modelConfig, providerConfig)
        val instructions = buildInstructions(baseInstructions = null, context = context)

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
        baseInstructions: String?,
        context: AgentExecutionContext,
    ): String {
        val namespace = namespaceService.findById(context.namespaceId)
        val namespaceBlock =
            buildString {
                appendLine()
                appendLine("""## Context: ${namespace?.name ?: context.namespaceId}""")
                namespace?.description?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            }.trimEnd()

        val integrationsWithDescription =
            integrationConfigService
                .findByParent(context.namespaceId)
                .filter { !it.description.isNullOrBlank() }
        val integrationsBlock =
            when {
                integrationsWithDescription.isEmpty() -> null
                else ->
                    buildString {
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

        return listOfNotNull(baseInstructions.takeUnless { it.isNullOrBlank() }, namespaceBlock, integrationsBlock, userBlock)
            .joinToString("\n")
    }

    companion object : KLogging()
}
