package io.whozoss.agentos.agent

import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.llmConfig.LlmConfig
import io.whozoss.agentos.llmConfig.LlmConfigService
import io.whozoss.agentos.llmModelConfig.LlmModelConfig
import io.whozoss.agentos.llmModelConfig.LlmModelConfigService
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
 * Implementation of [AgentService] that resolves agents from namespace-scoped
 * [LlmModelConfig] + [LlmConfig] entity pairs.
 *
 * Resolution strategy for a logical model name (e.g. "sonnet"):
 * 1. Load all [LlmModelConfig] entries for the namespace.
 * 2. Match by [LlmModelConfig.alias] first, then [LlmModelConfig.apiName] as fallback.
 * 3. Load the parent [LlmConfig] to get provider connectivity (apiType, baseUrl, apiKey).
 * 4. Build a [ChatClient] directly from the two entities via [ChatClientProvider].
 *
 * There is no fallback to a plugin-based registry — if no matching [LlmModelConfig]
 * exists for the namespace the call fails fast with a clear error.
 *
 * Agent definitions are currently hardcoded and reference a logical model name.
 * Once an Agent entity is introduced this service will resolve against that instead.
 */
@Service
class AgentServiceImpl(
    private val chatClientProvider: ChatClientProvider,
    private val toolRegistryService: ToolRegistryService,
    private val llmModelConfigService: LlmModelConfigService,
    private val llmConfigService: LlmConfigService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
) : AgentService {
    override fun findAgentByName(
        namePart: String,
        context: AgentExecutionContext,
    ): Agent {
        val (modelConfig, providerConfig) = resolveModelPair(namePart, context.namespaceId)
        // Use the canonical name from the config (alias if set, otherwise apiName)
        // rather than the raw input so the agent's identity is always stable.
        val canonicalName = modelConfig.alias ?: modelConfig.apiName
        return createAgentInstance(canonicalName, modelConfig, providerConfig, context)
    }

    override fun getDefaultAgent(context: AgentExecutionContext): Agent? {
        val modelConfig = findDefaultModelConfig(context.namespaceId) ?: return null
        val providerConfig = llmConfigService.getById(modelConfig.llmConfigId)
        return createAgentInstance(modelConfig.alias ?: modelConfig.apiName, modelConfig, providerConfig, context)
    }

    override fun getDefaultAgentName(namespaceId: UUID): String? {
        val modelConfig = findDefaultModelConfig(namespaceId) ?: return null
        return modelConfig.alias ?: modelConfig.apiName
    }

    override fun resolveAgentName(
        namePart: String,
        namespaceId: UUID,
    ): String? {
        val candidates = llmModelConfigService.findByNamespaceId(namespaceId)
        val match =
            candidates.filter { it.alias.equals(namePart, ignoreCase = true) }.maxByOrNull { it.priority }
                ?: candidates.filter { it.apiName.equals(namePart, ignoreCase = true) }.maxByOrNull { it.priority }
        return match?.let { it.alias ?: it.apiName }
    }

    // -------------------------------------------------------------------------
    // Resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Resolve a [LlmModelConfig] + [LlmConfig] pair for [name] within [namespaceId].
     *
     * Matching order: alias first, then apiName.
     * Throws [IllegalArgumentException] if no match is found.
     */
    private fun resolveModelPair(
        name: String,
        namespaceId: UUID,
    ): Pair<LlmModelConfig, LlmConfig> {
        val candidates = llmModelConfigService.findByNamespaceId(namespaceId)

        logger.debug { "[AgentService] Resolving '$name' in namespace $namespaceId — ${candidates.size} candidate(s)" }

        val aliasMatch = candidates
            .filter { it.alias.equals(name, ignoreCase = true) }
            .maxByOrNull { it.priority }
        val modelConfig = aliasMatch
            ?: candidates
                .filter { it.apiName.equals(name, ignoreCase = true) }
                .maxByOrNull { it.priority }
            ?: throw IllegalArgumentException(
                "No LlmModelConfig found for name '$name' in namespace $namespaceId. " +
                    "Configure an LlmModelConfig with alias or apiName matching '$name'.",
            )

        val matchedOn = if (aliasMatch != null) "alias" else "apiName"
        logger.info {
            "[AgentService] Resolved '$name' -> apiName='${modelConfig.apiName}' " +
                "(matched on $matchedOn, priority=${modelConfig.priority}, llmConfigId=${modelConfig.llmConfigId})"
        }

        val providerConfig = llmConfigService.getById(modelConfig.llmConfigId)
        logger.debug { "[AgentService] Provider resolved: name='${providerConfig.name}' apiType=${providerConfig.apiType}" }
        return modelConfig to providerConfig
    }

    /**
     * Pick the highest-priority [LlmModelConfig] for the namespace as the default.
     * Falls back to insertion order among configs with equal priority.
     */
    private fun findDefaultModelConfig(namespaceId: UUID): LlmModelConfig? =
        llmModelConfigService.findByNamespaceId(namespaceId)
            .maxByOrNull { it.priority }

    // -------------------------------------------------------------------------
    // Agent instantiation
    // -------------------------------------------------------------------------

    /**
     * Build a live [AgentSimple] instance from the resolved entity pair, scoped to [context].
     *
     * [agentName] is the logical name used to identify this agent (alias if set, otherwise apiName).
     * The namespace description and user context are appended to the system instructions.
     */
    private fun createAgentInstance(
        agentName: String,
        modelConfig: LlmModelConfig,
        providerConfig: LlmConfig,
        context: AgentExecutionContext,
    ): Agent {
        logger.info { "[AgentService] Creating agent '$agentName' for namespace ${context.namespaceId}" }

        val tools = toolRegistryService.resolveToolsForNamespace(context.namespaceId)
        logger.info {
            "[AgentService] Loaded ${tools.size} tool(s) " +
                "(sample-5: ${tools.take(5).map { it.name }}) for agent: $agentName"
        }

        val chatClient = chatClientProvider.getChatClient(modelConfig, providerConfig)

        // Build a minimal AiModel so AgentSimple keeps its current constructor unchanged.
        // This will be replaced once the Agent entity is introduced.
        val instructions = buildInstructions(baseInstructions = null, context = context)
        val model =
            AiModel(
                metadata = EntityMetadata(id = UUID.nameUUIDFromBytes(agentName.toByteArray())),
                name = agentName,
                description = agentName,
                modelName = modelConfig.apiName,
                providerName = providerConfig.name,
                temperature = modelConfig.temperature,
                maxTokens = modelConfig.maxTokens,
                instructions = instructions,
            )

        return AgentSimple(
            metadata = EntityMetadata(id = UUID.nameUUIDFromBytes(agentName.toByteArray())),
            model = model,
            chatClient = chatClient,
            tools = tools,
        )
    }

    /**
     * Compose the final system instructions for the agent.
     *
     * Appends a namespace context block (always) and an optional user context block
     * into the privileged system-prompt channel so they are never compacted away
     * by the provider, regardless of conversation length.
     *
     * [baseInstructions] will be populated from the Agent entity once it exists.
     */
    private fun buildInstructions(
        baseInstructions: String?,
        context: AgentExecutionContext,
    ): String {
        val namespace = namespaceService.findById(context.namespaceId)
        val namespaceBlock =
            buildString {
                appendLine()
                appendLine("## Context: ${namespace?.name ?: context.namespaceId}")
                val description = namespace?.description
                if (!description.isNullOrBlank()) {
                    appendLine(description)
                }
            }.trimEnd()

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

        val base = if (baseInstructions.isNullOrBlank()) namespaceBlock else "$baseInstructions\n$namespaceBlock"
        return if (userBlock != null) "$base\n$userBlock" else base
    }

    companion object : KLogging()
}
