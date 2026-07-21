package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.agentConfig.AgentDocumentResolver
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.CompressingChatClient
import io.whozoss.agentos.delegation.DelegationTool
import io.whozoss.agentos.delegation.SubCaseManager
import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.metrics.ToolMetricsService
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.redirect.globToRegex
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
import io.whozoss.agentos.util.IdCompressorService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
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
    private val agentConfigService: AgentConfigService,
    private val intentionGenerator: AgentIntentionGenerator,
    private val confirmationManager: ConfirmationManager,
    private val objectMapper: ObjectMapper,
    private val toolRegistryService: ToolRegistryService,
    private val toolMetricsService: ToolMetricsService,
    private val caseEventService: CaseEventService,
    private val idCompressorService: IdCompressorService,
    private val exchangeStorageService: ExchangeStorageService,
    private val exchangeCapabilityService: ExchangeCapabilityService,
    private val agentDocumentResolver: AgentDocumentResolver,
) : AgentService {
    /**
     * Resolves an agent by name for a given [context].
     *
     * **Matching semantics differ by path:**
     * - `userId != null` path: loads all accessible agents (platform + deployed) and filters
     *   client-side with an exact case-insensitive match (`equals(ignoreCase = true)`).
     * - `userId == null` path: delegates to [AgentConfigServiceImpl.findByName] which performs
     *   a case-insensitive exact match, then falls back to platform agents.
     * - [resolveAgentName] with `userId != null` uses a Cypher `STARTS WITH` prefix match
     *   (autocomplete — a different call site from this method).
     *
     * **Scoping / shadowing rule (both paths):**
     * Platform agents (namespaceId = null) are shadowed by namespace-scoped agents with the
     * same name. Uniqueness is enforced *per level*: two configs at the same level (both
     * namespace-scoped, or both platform) with the same name is an error; one config at each
     * level is resolved by preferring the namespace-scoped one.
     */
    override suspend fun findAgentByName(
        namePart: String,
        context: AgentExecutionContext,
        subCaseManager: SubCaseManager?,
    ): Agent {
        require(namePart.isNotBlank()) { "Blank agent name, cannot resolve agent" }
        val agentConfig =
            if (context.userId != null) {
                val agentConfigs =
                    agentConfigService
                        .findDeployedByNamespaceIdAndUserIdAndName(
                            namespaceId = context.namespaceId,
                            userId = context.userId,
                            agentName = namePart.lowercase(),
                        ).filter { it.name.equals(namePart, ignoreCase = true) }
                resolveWithShadowing(agentConfigs, namePart, context.namespaceId)
            } else {
                agentConfigService.findByName(context.namespaceId, namePart)
            } ?: throw IllegalArgumentException(
                "No AgentConfig found for name '$namePart' in namespace ${context.namespaceId}.",
            )
        val resolvedUser = context.userId?.let { runCatching { userService.findById(it) }.getOrNull() }
        val definition = resolveAgentDefinition(agentConfig, context, resolvedUser, subCaseManager)
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
        namespaceId: UUID?,
        userId: UUID?,
    ): String? =
        if (userId != null) {
            agentConfigService
                .findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId = namespaceId,
                    userId = userId,
                    agentName = namePart,
                ).firstOrNull()
                ?.name
        } else {
            agentConfigService.findByName(namespaceId, namePart)?.name
        }

    // -------------------------------------------------------------------------
    // Shadowing / uniqueness helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a single [AgentConfig] from [candidates] applying the platform-vs-namespace
     * shadowing rule:
     * - Namespace-scoped configs shadow platform configs with the same name.
     * - Uniqueness is enforced *per level*: duplicate names at the same level are an error.
     * - Returns null when [candidates] is empty.
     *
     * The precedence order is: namespace > platform.
     */
    private fun resolveWithShadowing(
        candidates: List<AgentConfig>,
        namePart: String,
        namespaceId: UUID,
    ): AgentConfig? {
        val (namespaceCandidates, platformCandidates) = candidates.partition { it.namespaceId != null }

        val duplicatesAtSameLevel = namespaceCandidates.size > 1 || platformCandidates.size > 1
        if (duplicatesAtSameLevel) {
            val culprit = if (namespaceCandidates.size > 1) "namespace" else "platform"
            throw IllegalArgumentException(
                "No unique AgentConfig found for name '$namePart': " +
                    "${if (namespaceCandidates.size > 1) namespaceCandidates.size else platformCandidates.size} " +
                    "$culprit-level configs match in namespace $namespaceId.",
            )
        }

        // Namespace-scoped agent shadows the platform agent when both exist.
        return namespaceCandidates.firstOrNull() ?: platformCandidates.firstOrNull()
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
        agentConfig: AgentConfig,
        context: AgentExecutionContext,
        resolvedUser: User?,
        subCaseManager: SubCaseManager? = null,
    ): ResolvedAgentDefinition {
        val baseModel =
            agentConfig.modelName?.let { aiModelService.findAiModel(context.namespaceId, it) }
                ?: findDefaultModelConfig(context.namespaceId)
                ?: throw IllegalArgumentException(
                    "AgentConfig '${agentConfig.name}' could not resolve an AiModel " +
                        "(modelName=${agentConfig.modelName}, namespace=${context.namespaceId}).",
                )
        val (modelConfig, providerConfig) =
            applyOverlaysToModel(
                baseModel = baseModel,
                namespaceId = context.namespaceId,
                userId = context.userId,
            )
        val effectiveIntegrationConfigs = integrationConfigService.findEffective(context.namespaceId, context.userId)
        val namespace = namespaceService.findById(context.namespaceId)
        val namespaceSystemPrompt =
            buildNamespaceSystemPrompt(
                namespace = namespace,
                context = context,
                resolvedUser = resolvedUser,
                effectiveIntegrationConfigs = effectiveIntegrationConfigs,
            )
        val instructions =
            buildInstructions(
                baseInstructions = agentConfig.instructions,
                agentIntegrations = agentConfig.integrations,
                resolvedUser = resolvedUser,
                effectiveIntegrationConfigs = effectiveIntegrationConfigs,
                docs = agentConfig.docs,
            )
        val toolContext =
            context.toToolContext(
                userExternalId = context.userId?.let { userService.findById(it) }?.externalId,
                agentName = agentConfig.name,
            )
        val baseTools =
            toolResolverService.resolveToolsForRun(
                agentIntegrations = agentConfig.integrations,
                context = toolContext,
                allIntegrationConfigs = effectiveIntegrationConfigs,
            )
        // Delegation and exchange tools are appended after resolveToolsForRun's own de-dup, so
        // de-dup the combined set by tool name (shared with the resolver) to avoid a duplicate-name
        // collision (e.g. a user FILE_ACCESS integration named "case-exchange") crashing downstream.
        val tools =
            toolResolverService.dedupToolsByName(
                baseTools +
                    listOfNotNull(
                        subCaseManager?.let {
                            buildDelegationTools(
                                config = agentConfig,
                                context = context,
                                subCaseManager = it,
                            )
                        },
                    ) +
                    buildExchangeTools(agentConfig, context, toolContext),
            )

        return ResolvedAgentDefinition(
            agentConfigId = agentConfig.metadata.id,
            name = agentConfig.name,
            systemPrompt = namespaceSystemPrompt.takeUnless { it.isBlank() },
            instructions = instructions.takeUnless { it.isBlank() },
            resolvedModelApiName = modelConfig.apiModelName,
            resolvedProviderName = providerConfig.name,
            resolvedModelId = modelConfig.metadata.id,
            resolvedProviderId = providerConfig.metadata.id,
            resolvedModel = modelConfig,
            resolvedProvider = providerConfig,
            tools = tools,
            advancedExecution = agentConfig.advancedExecution,
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
            agentId = definition.agentConfigId,
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
     * Resolve the [AiProvider] for a pre-resolved [baseModel].
     *
     * When [userId] is non-null, delegates to [AiProviderService.resolveProvider] which
     * fetches all four overlay layers in a single query and folds them by precedence.
     * When null, falls back to a direct lookup by id — preserves Epic 4 behaviour.
     */
    private fun applyOverlaysToModel(
        baseModel: AiModel,
        namespaceId: UUID,
        userId: UUID?,
    ): Pair<AiModel, AiProvider> {
        val baseProvider = aiProviderService.getById(baseModel.aiProviderId)
        val providerConfig =
            if (userId != null) {
                aiProviderService.resolveProvider(namespaceId, userId, baseProvider.name)
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
        agentId: UUID,
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

        val chatClient = chatClientProvider.getChatClient(modelConfig, providerConfig, context.caseId?.toString())

        return if (advancedExecution) {
            val compressingChatClient = CompressingChatClient(chatClient, idCompressorService)
            val advancedContext =
                AgentAdvancedContext(
                    chatClient = compressingChatClient,
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
                metadata = EntityMetadata(id = agentId),
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
                toolMetricsService = toolMetricsService,
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
        namespace: io.whozoss.agentos.namespace.Namespace?,
        context: AgentExecutionContext,
        resolvedUser: User?,
        effectiveIntegrationConfigs: List<IntegrationConfig>,
    ): String {
        val namespaceId = context.namespaceId
        val toolContext = context.toToolContext(resolvedUser?.externalId, null)
        val integrationDescriptions =
            coroutineScope {
                effectiveIntegrationConfigs
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
    private suspend fun buildInstructions(
        baseInstructions: String?,
        agentIntegrations: Map<String, List<String>?>?,
        resolvedUser: User?,
        effectiveIntegrationConfigs: List<IntegrationConfig>,
        docs: List<String>? = null,
    ): String {
        val integrationsBlock =
            when {
                agentIntegrations == null -> {
                    null
                }

                else -> {
                    val listed =
                        effectiveIntegrationConfigs
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

        val docsBlock = agentDocumentResolver.buildDocsBlock(docs)

        return listOfNotNull(baseInstructions.takeUnless { it.isNullOrBlank() }, integrationsBlock, userBlock, docsBlock)
            .joinToString("\n")
    }

    /**
     * Resolves the [DelegationTool] for [config] if delegation is configured.
     *
     * Resolution steps:
     * 1. Skip entirely when [subCaseManager] is null (no delegation support in this call
     *    path) or [config.subAgents] is null/empty.
     * 2. Fetch the agents accessible to the current user in the namespace.
     * 3. Filter them by matching each accessible agent name against the [config.subAgents]
     *    glob patterns via [globToRegex] (anchored, case-insensitive, `*` = any sequence).
     *    Examples: `"*Fixer"` matches `BugFixer` and `StoryFixer`; `"*"` matches all agents.
     * 4. Build a [DelegationTool] only when the resolved allowlist is non-empty and a
     *    [context.caseId] is available (no delegation outside a live case).
     */
    private fun buildDelegationTools(
        config: AgentConfig,
        context: AgentExecutionContext,
        subCaseManager: SubCaseManager,
    ): DelegationTool? {
        val patterns = config.subAgents?.filter { it.isNotBlank() }
        if (patterns.isNullOrEmpty() || context.caseId == null) return null

        val accessibleNames =
            if (context.userId != null) {
                agentConfigService
                    .findDeployedByNamespaceIdAndUserIdAndName(
                        namespaceId = context.namespaceId,
                        userId = context.userId,
                        agentName = null,
                    ).map { it.name }
            } else {
                agentConfigService
                    .findByNamespace(context.namespaceId, withDisabled = false)
                    .map { it.name }
            }

        val regexes = patterns.map { globToRegex(it) }
        val allowedAgents = accessibleNames.filter { name -> regexes.any { it.matches(name) } }

        if (allowedAgents.isEmpty()) {
            logger.warn { "DelegationTool: no accessible agents matched patterns $patterns for agent '${config.name}'" }
            return null
        }

        logger.info { "Adding DelegationTool for agent '${config.name}' with allowedAgents=$allowedAgents" }
        return DelegationTool(
            subCaseManager = subCaseManager,
            parentCaseId = context.caseId,
            namespaceId = context.namespaceId,
            allowedAgents = allowedAgents,
            loadCaseEvents = { caseId -> caseEventService.findByParent(caseId) },
        )
    }

    /**
     * Builds the file-exchange tools enabled on [config], scoped to the current run.
     *
     * The case/namespace exchange is exposed to the agent as a **built-in integration of the
     * file-plugin** (integration type [ExchangeIntegrationTypes.FILE_ACCESS]): we point the plugin's own tools
     * (list/read/search/edit/move/remove) at the exchange directory for the scope. The per-case
     * root is computed here, where [context.caseId] is available, and passed to the plugin as
     * `rootPath` — so the SDK [ToolContext] never has to carry a case id.
     *
     * Note on confirmation: the plugin's edit/move/remove confirmation gate only applies to
     * advanced-execution agents. A non-advanced agent granted write access (`readOnly=false`)
     * executes these without a prompt — the same behaviour as any other writable FILE_ACCESS
     * integration, not a guarantee specific to the exchange.
     *
     * Note on sensitive files: the file-plugin tools apply their own sensitive-file deny-list
     * (SensitiveFilePatterns.DEFAULT_PATTERNS), so an agent cannot list/read files such as keys or
     * `.env` even when they sit in the exchange. This is intentionally stricter than the user-facing
     * [io.whozoss.agentos.exchange.ExchangeController] path, which applies no deny-list (users manage
     * their own files); the two views of the same directory can therefore differ for such files.
     *
     * Gating is fail-closed (enablement lives in [AgentConfig.integrations], not a dedicated flag):
     * - if the file-plugin is not loaded ([ExchangeIntegrationTypes.FILE_ACCESS] absent) → no tools;
     * - case exchange (read/write) requires the [ExchangeIntegrationTypes.CASE] key AND a live
     *   [context.caseId];
     * - namespace exchange requires the [ExchangeIntegrationTypes.NAMESPACE] key; the agent inherits
     *   the invoking user's namespace right — read/write when the user holds Namespace WRITE
     *   (admin/super-admin), read-only otherwise.
     */
    private fun buildExchangeTools(
        config: AgentConfig,
        context: AgentExecutionContext,
        toolContext: ToolContext,
    ): List<StandardTool<*>> {
        val filePlugin = toolRegistryService.findPlugin(ExchangeIntegrationTypes.FILE_ACCESS) ?: return emptyList()

        // Point the file-plugin's own tools at the exchange directory for the scope, honouring
        // the per-tool allowlist carried as the integrations-map value (null = all tools).
        fun grant(
            root: Path,
            readOnly: Boolean,
            configName: String,
            allowedTools: List<String>?,
        ): List<StandardTool<*>> {
            // Materialise the root before building the plugin tools even for a read-only grant: the
            // file-plugin's BoundaryPathResolver canonicalises rootPath (toRealPath) at construction,
            // which throws if the directory does not exist. This is why a read-only resolution (e.g.
            // the debug getDefinition endpoint) still creates an empty scope dir.
            Files.createDirectories(root)
            val cfg =
                objectMapper
                    .createObjectNode()
                    .put("rootPath", root.toAbsolutePath().toString())
                    .put("readOnly", readOnly)
                    // Align the agent read tool's size cap with the exchange's own read limit, so an
                    // agent can read back a file the controller read/download path serves (the plugin
                    // otherwise falls back to its smaller built-in default). The plugin key is megabytes.
                    .put("readMaxSizeMb", (exchangeStorageService.readMaxSizeBytes / (1024 * 1024)).coerceAtLeast(1))
            logger.info { "Granting $configName (FILE_ACCESS, readOnly=$readOnly) to agent '${config.name}' at $root" }
            // Honour the per-tool allowlist via the same matcher every other integration uses
            // (accepts both bare and `configName__tool` forms); null = all tools.
            return filePlugin
                .provideTools(cfg, configName, toolContext)
                .filter { toolResolverService.isToolAllowed(it.name, configName, allowedTools) }
        }

        val integrations = config.integrations ?: emptyMap()
        val tools = mutableListOf<StandardTool<*>>()
        val caseId = context.caseId
        val caseCreatedAt = context.caseCreatedAt
        if (integrations.containsKey(ExchangeIntegrationTypes.CASE) && caseId != null && caseCreatedAt != null) {
            // The agent gets read/write on the case exchange by design (it produces files during a run).
            // User-facing write is separately gated: the exchange upload/delete endpoints require Case
            // WRITE via @PreAuthorize, and the manifest exposes the computed ExchangeCapability.
            tools +=
                grant(
                    exchangeStorageService.caseRoot(context.namespaceId, caseId, caseCreatedAt),
                    readOnly = false,
                    configName = ExchangeIntegrationTypes.CASE_CONFIG_NAME,
                    allowedTools = integrations[ExchangeIntegrationTypes.CASE],
                )
        }
        if (integrations.containsKey(ExchangeIntegrationTypes.NAMESPACE)) {
            // The agent inherits the invoking user's namespace right: read/write for a namespace
            // admin (Namespace WRITE, super-admin included), read-only for a plain member.
            val userCanWriteNamespace =
                context.userId?.let {
                    exchangeCapabilityService.canWrite(
                        it.toString(),
                        EntityType.NAMESPACE,
                        context.namespaceId.toString(),
                    )
                } ?: false
            tools +=
                grant(
                    exchangeStorageService.namespaceRoot(context.namespaceId),
                    readOnly = !userCanWriteNamespace,
                    configName = ExchangeIntegrationTypes.NAMESPACE_CONFIG_NAME,
                    allowedTools = integrations[ExchangeIntegrationTypes.NAMESPACE],
                )
        }
        return tools
    }

    companion object : KLogging()
}
