package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.auth.CredentialProviderFactory
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.user.User
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Resolves the tools an [IntegrationConfig] yields for a user, without an agent and without a case,
 * so an admin can check a config right after saving it.
 *
 * The stored row is used as is: the 4-tier overlay merge that an agent run applies
 * (`IntegrationConfigService.findEffective`) is NOT applied here, so a user-scoped override of the
 * same name is not folded in. The plugin is invoked exactly like `ToolResolverService` does for a
 * run — `provideTools(parameters, name, context)` with a `credentialProvider` when an auth setting
 * is bound — which means the preview inherits the plugins' connection behaviour: `MCP_HTTP` opens a
 * fresh connection on every `provideTools` call (#1133), so each preview of an MCP_HTTP config
 * connects to the remote server, like the agent-definition preview already does.
 *
 * No agent-level allowlist is applied and nothing is persisted.
 */
@Service
class IntegrationConfigToolPreviewService(
    private val toolRegistryService: ToolRegistryService,
    private val credentialProviderFactory: CredentialProviderFactory,
    private val integrationsProperties: IntegrationsProperties,
) {
    /**
     * Previews [config] in [namespaceId] for [user].
     *
     * The credential provider (built with no case, so OAuth types resolve through the direct
     * lookup only and never start an interactive flow) is handed to `provideTools` only; the
     * `describeNamespace` call receives the context without it.
     *
     * Throws [UnprocessableEntityException] when no plugin is loaded for the config's type.
     */
    fun preview(
        config: IntegrationConfig,
        namespaceId: UUID,
        user: User,
    ): IntegrationConfigToolPreview {
        val plugin =
            toolRegistryService.findPlugin(config.integrationType)
                ?: throw UnprocessableEntityException(
                    "No plugin is loaded for integration type '${config.integrationType}'",
                )
        val baseContext =
            ToolContext(
                namespaceId = namespaceId,
                userId = user.id,
                userExternalId = user.externalId,
                caseEvents = emptyList(),
                agentName = null,
            )
        val credentialProvider =
            config.authSettingName?.let { authSettingName ->
                credentialProviderFactory.forRun(
                    namespaceId = namespaceId,
                    userId = user.id,
                    caseId = null,
                    agentName = null,
                    emitEvent = null,
                )(authSettingName)
            }
        val toolContext = baseContext.copy(credentialProvider = credentialProvider)
        val (tools, error) = provideToolsSafely(plugin, config, toolContext)
        return IntegrationConfigToolPreview(
            integrationType = config.integrationType,
            configName = config.name,
            namespaceDescription = describeNamespaceSafely(plugin, config, baseContext),
            tools = tools,
            error = error,
        )
    }

    /**
     * The mapped tools, or an empty list plus the failure rendered as `ExceptionClass: message`.
     * One `runBlocking` covers the whole mapping: every tool's confirmation mode is a suspend call.
     */
    private fun provideToolsSafely(
        plugin: ToolPlugin,
        config: IntegrationConfig,
        context: ToolContext,
    ): Pair<List<IntegrationConfigToolPreview.ToolPreview>, String?> =
        try {
            val tools = plugin.provideTools(config = config.parameters, configName = config.name, context = context)
            runBlocking { tools.map { tool -> toPreview(tool, context) } } to null
        } catch (e: Exception) {
            logger.warn(e) {
                "[ToolPreview] provideTools failed for integration '${config.name}' (type '${config.integrationType}')"
            }
            emptyList<IntegrationConfigToolPreview.ToolPreview>() to describeFailure(e)
        }

    /** The confirmation mode is the one the tool reports for a call without arguments. */
    private suspend fun toPreview(
        tool: StandardTool<*>,
        context: ToolContext,
    ): IntegrationConfigToolPreview.ToolPreview =
        IntegrationConfigToolPreview.ToolPreview(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema,
            confirmationMode = tool.getConfirmationMode(argsJson = null, context = context),
        )

    /**
     * The plugin's namespace line, or null when it fails or does not answer within
     * [IntegrationsProperties.previewDescribeNamespaceTimeoutMs]. The bound is best effort:
     * `withTimeoutOrNull` cancels a cooperatively suspending implementation, while one that blocks
     * inside the suspend function is only abandoned and still holds the thread until it returns.
     */
    private fun describeNamespaceSafely(
        plugin: ToolPlugin,
        config: IntegrationConfig,
        context: ToolContext,
    ): String? =
        try {
            runBlocking {
                withTimeoutOrNull(integrationsProperties.previewDescribeNamespaceTimeoutMs) {
                    plugin.describeNamespace(config = config.parameters, configName = config.name, context = context)
                }
            }
        } catch (e: Exception) {
            logger.warn(e) {
                "[ToolPreview] describeNamespace failed for integration '${config.name}' " +
                    "(type '${config.integrationType}')"
            }
            null
        }

    /** `ExceptionClass: message`, shown verbatim by the UI; never renders a `null` literal. */
    private fun describeFailure(e: Exception): String {
        val type = e::class.simpleName ?: e::class.java.name
        val message = e.message ?: "no message"
        return "$type: $message"
    }

    companion object : KLogging()
}
