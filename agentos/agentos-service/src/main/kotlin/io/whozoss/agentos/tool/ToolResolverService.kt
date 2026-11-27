package io.whozoss.agentos.tool

import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationTypeConstraints
import io.whozoss.agentos.authSetting.toDataMap
import io.whozoss.agentos.sdk.auth.CredentialProvider
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import org.springframework.stereotype.Service

@Service
class ToolResolverService(
    private val toolRegistryService: ToolRegistryService,
    private val connectionPolicies: io.whozoss.agentos.auth.ConnectionPolicyRegistry = io.whozoss.agentos.auth.ConnectionPolicyRegistry(),
    private val authSettingService: io.whozoss.agentos.authSetting.AuthSettingService? = null,
) {
    /**
     * Resolves the tool set for an agent run from the already-merged [allIntegrationConfigs]
     * (the 4-tier overlay is applied upstream by the integration config service).
     *
     * The [context] is passed verbatim to each plugin, giving them access to the runtime
     * identity (namespace, user, external id, agent name, case events). A null
     * [ToolContext.userId] is accepted: tools still resolve, and whether a plugin receives a
     * `credentialProvider` depends solely on [credentialProviderFactory]. A factory returning
     * `null` (the default here, and what `AgentServiceImpl` does for a run without a user)
     * leaves the plugin without one.
     *
     * @param agentIntegrations Optional integration filter from AgentConfig.integrations.
     *   When null, the agent declares no explicit integration bindings, but it still receives
     *   every config with `autoGrant == true` in [allIntegrationConfigs]: this resolver no longer
     *   returns no tools in that case. The built-in exchange scopes remain granted outside this
     *   resolver by [io.whozoss.agentos.exchange.ExchangeToolGrantService], whose platform defaults
     *   can hand the file-plugin tools to an agent that declares nothing.
     *
     *   Resolution is an **union minus opt-out**:
     *
     *   | Config in `agentIntegrations`? | `autoGrant` | Included? | `allowedNames` passed to [extractTools] |
     *   |---|---|---|---|
     *   | named, with a non-empty list   | any   | yes | the declared list |
     *   | named, with an empty list      | any   | **no** (sovereign opt-out) | n/a |
     *   | not named                      | true  | yes | `null` (all tools) |
     *   | not named                      | false | no | n/a |
     *
     *   The explicit empty-list opt-out always wins over `autoGrant`: it is what protects an
     *   autonomous, webhook-triggered agent (nobody listening) from a tool question with no
     *   respondent that would block the case forever.
     * @param context Runtime context forwarded to each [ToolPlugin.provideTools] call.
     * @param credentialProviderFactory Builds the [CredentialProvider] for a config's
     *   `authSettingName`; a `null` result leaves the context without a provider.
     * @return The resolved tools and the names of the integrations whose plugin threw or is not loaded
     *   (see [ToolResolution]).
     *   A failing integration never fails the run: its tools are missing and its name is reported, its full
     *   error is logged here.
     */
    fun resolveToolsForRun(
        agentIntegrations: Map<String, List<String>?>? = null,
        context: ToolContext,
        allIntegrationConfigs: List<IntegrationConfig>,
        credentialProviderFactory: (String) -> CredentialProvider? = { null },
    ): ToolResolution {
        // Sovereign opt-out: the agent explicitly mapped this integration to an empty tool list.
        // This is never mutated back into AgentConfig.integrations; it is purely local to this resolver.
        val optedOutNames =
            agentIntegrations
                ?.filterValues { it != null && it.isEmpty() }
                ?.keys
                ?: emptySet()
        val explicitlyNamedNames = agentIntegrations?.keys ?: emptySet()
        val integrationConfigs =
            allIntegrationConfigs.filter { config ->
                config.name !in optedOutNames &&
                    (config.name in explicitlyNamedNames || config.autoGrant)
            }
        val failedIntegrations = mutableListOf<String>()
        val allTools =
            integrationConfigs
                .mapNotNull { config ->
                    toolRegistryService
                        .findPlugin(config.integrationType)
                        .also {
                            if (it == null) {
                                logger.warn {
                                    "[ToolResolver] No plugin found for type ${config.integrationType} " +
                                        "(integration '${config.name}')"
                                }
                                // A requested integration whose plugin is not loaded is a load failure, not an
                                // integration without tools: report it like a throwing plugin so it is not silent.
                                // GIT_REPOSITORY is a namespace capability that no ToolPlugin ever answers.
                                if (config.integrationType != IntegrationTypeConstraints.GIT_REPOSITORY_TYPE) {
                                    failedIntegrations.add(config.name)
                                }
                            }
                        }?.let { plugin ->
                            extractTools(
                                allowedNames = agentIntegrations?.get(config.name),
                                config = config,
                                plugin = plugin,
                                context = context,
                                credentialProviderFactory = credentialProviderFactory,
                                onFailure = { failedIntegrations.add(config.name) },
                            )
                        }
                }.flatten()

        return ToolResolution(
            tools = dedupToolsByName(allTools),
            failedIntegrations = failedIntegrations.distinct(),
        )
    }

    /**
     * De-duplicate tools by name, keeping the first occurrence and warning on each conflict,
     * then sort the result by name using binary (locale-independent) ordering.
     *
     * Sorting after deduplication is intentional: sorting before would change which duplicate
     * is kept (the first alphabetically rather than the first encountered), breaking the
     * existing selection semantics.
     *
     * The binary sort (compareBy with String.compareTo using ordinal comparison) is independent
     * of the JVM default locale, ensuring a stable, reproducible order across deployments.
     * This matters for prompt-cache stability: OpenAI's prefix cache is invalidated by any
     * change in tool name order, so a deterministic order maximises cache reuse.
     *
     * Shared so every tool source (resolver, delegation, exchange) reconciles collisions identically.
     */
    fun dedupToolsByName(tools: List<StandardTool<*>>): List<StandardTool<*>> =
        tools
            .groupBy { it.name }
            .map { (name, duplicates) ->
                if (duplicates.size > 1) {
                    logger.warn { "[ToolResolver] Tool name conflict: '$name' present ${duplicates.size} times, keeping the first one." }
                }
                duplicates.first()
            }.sortedBy { it.name }

    /**
     * Asks [plugin] for the tools of [config]. A plugin exception is logged in full and reported through
     * [onFailure], then yields no tools; it never propagates.
     */
    private fun extractTools(
        allowedNames: List<String>?,
        config: IntegrationConfig,
        plugin: ToolPlugin,
        context: ToolContext,
        credentialProviderFactory: (String) -> CredentialProvider?,
        onFailure: () -> Unit,
    ): List<StandardTool<*>>? {
        var boundContext = context.copy(credentialProvider = null, authSettingName = null, connectionCapability = null)
        val tools =
            try {
                val name = config.authSettingName
                if (name != null) {
                    var pinned: io.whozoss.agentos.authSetting.AuthSetting? = null
                    fun setting(): io.whozoss.agentos.authSetting.AuthSetting {
                        val user = context.userId ?: error(io.whozoss.agentos.auth.ConnectionPolicyRegistry.DENIED)
                        val current = checkNotNull(authSettingService).resolveAuthSetting(context.namespaceId, user, name)
                        pinned?.let { base ->
                            val old = base.toDataMap()
                            val fresh = current.toDataMap()
                            check(current.metadata.id == base.metadata.id && current.authType == base.authType &&
                                listOf("authorizationUrl", "tokenUrl", "discoveryUrl", "resourceUrl").all { old[it] == fresh[it] }) { io.whozoss.agentos.auth.ConnectionPolicyRegistry.DENIED }
                        }
                        return current
                    }
                    // Legacy unit callers without a service keep their old factory contract. The real
                    // Spring runtime always injects AuthSettingService, including direct execution.
                    if (authSettingService != null) connectionPolicies.requireIntegration(setting(), config.integrationType)
                    if (authSettingService != null) setting().takeIf { connectionPolicies.isGoverned(it) }?.let { pinned = it }
                    val original = credentialProviderFactory(name)
                    val guarded: CredentialProvider? = when {
                        authSettingService == null -> original
                        else -> original?.let { supplier -> {
                            setting().also { connectionPolicies.requireIntegration(it, config.integrationType) }
                            val credential = supplier()
                            if (credential != null) connectionPolicies.requireGrant(setting(), credential, context.userId!!)
                            credential
                        } }
                    }
                    val capability = if (authSettingService != null) object : io.whozoss.agentos.sdk.auth.ConnectionCapability {
                        override fun requireActive() { connectionPolicies.requireIntegration(setting(), config.integrationType) }
                        override fun isGoverned(): Boolean = connectionPolicies.isGoverned(setting())
                        override fun authorize(destination: String): io.whozoss.agentos.sdk.credential.Credential? {
                            val before = setting()
                            connectionPolicies.requireResource(before, config.integrationType, destination)
                            val credential = guarded?.invoke()
                            val after = setting()
                            check(after.metadata.id == before.metadata.id) { io.whozoss.agentos.auth.ConnectionPolicyRegistry.DENIED }
                            connectionPolicies.requireResource(after, config.integrationType, destination)
                            if (credential != null) connectionPolicies.requireGrant(after, credential, context.userId!!)
                            check(credential != null || !connectionPolicies.isGoverned(after)) { io.whozoss.agentos.auth.ConnectionPolicyRegistry.DENIED }
                            return credential
                        }
                    } else null
                    boundContext = context.copy(credentialProvider = guarded, authSettingName = name.takeIf { guarded != null || capability != null }, connectionCapability = capability)
                }
                val enrichedContext = boundContext
                plugin.provideTools(
                    config = config.parameters,
                    configName = config.name,
                    context = enrichedContext,
                )
            } catch (e: Exception) {
                logger.error(e) {
                    "[ToolResolver] Error instantiating tools for integration '${config.name}' " +
                        "(type '${config.integrationType}'): ${e.message}"
                }
                onFailure()
                emptyList()
            }
        logger.debug {
            "[ToolResolver] Plugin ${config.integrationType} provided ${tools.size} tool(s) " +
                "for integration '${config.name}': ${
                    tools.joinToString(", ") { it.name }
                }"
        }
        return tools
            .map { tool -> boundContext.connectionCapability?.let { GuardedConnectionTool(tool, boundContext) } ?: tool }
            .filter { tool ->
                isToolAllowed(
                    toolName = tool.name,
                    integrationKey = config.name,
                    allowedNames = allowedNames,
                )
            }.also { tools ->
                logger.debug {
                    "[ToolResolver] Resolved ${tools.size} tools for integration ${config.name} on agent ${context.agentName}"
                }
            }
    }

    internal fun isToolAllowed(
        toolName: String,
        integrationKey: String,
        allowedNames: List<String>?,
    ): Boolean {
        if (allowedNames == null) return true
        return allowedNames.any { allowed ->
            toolName == allowed || toolName == "${integrationKey}__$allowed"
        }
    }

    companion object : KLogging()
}
