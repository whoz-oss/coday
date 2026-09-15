package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.auth.CredentialProviderFactory
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.user.User
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
 * **Time bounds.** Both plugin calls run off the request thread and the request waits for each at
 * most [IntegrationsProperties.previewProvideToolsTimeoutMs] (resp.
 * [IntegrationsProperties.previewDescribeNamespaceTimeoutMs]), whatever timeouts the config itself
 * declares. Past the bound the call is abandoned, not interrupted:
 * the bundled plugins do not tolerate an interrupt (it breaks their own cleanup and shared connection
 * pools), so an abandoned call keeps its worker until the plugin returns on its own, and its result is
 * discarded (an `MCP_HTTP` connection it opens is then left open, as on every successful preview).
 * At most [IntegrationsProperties.previewMaxConcurrentPluginCalls] calls hold a worker at once, across
 * all namespaces; a call that finds none free is refused at once and never reaches the plugin.
 *
 * No agent-level allowlist is applied and nothing is persisted.
 */
@Service
class IntegrationConfigToolPreviewService(
    private val toolRegistryService: ToolRegistryService,
    private val credentialProviderFactory: CredentialProviderFactory,
    private val integrationsProperties: IntegrationsProperties,
) {
    init {
        require(integrationsProperties.previewMaxConcurrentPluginCalls > 0) {
            "agentos.integrations.preview-max-concurrent-plugin-calls must be positive, " +
                "got ${integrationsProperties.previewMaxConcurrentPluginCalls}"
        }
    }

    /** One permit per plugin call holding a worker, released when the call really ends. */
    private val workerPermits = Semaphore(integrationsProperties.previewMaxConcurrentPluginCalls)

    /**
     * Workers for the plugin calls. A `limitedParallelism` view of `Dispatchers.IO` is elastic: it does
     * not take threads from the pool agent runs share. With one permit per worker, a call never queues.
     */
    private val pluginCallScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO.limitedParallelism(
                    parallelism = integrationsProperties.previewMaxConcurrentPluginCalls,
                    name = "tool-preview",
                ),
        )

    /**
     * Previews [config] in [namespaceId] for [user].
     *
     * The credential provider (built for the preview, so OAuth types resolve through the direct
     * lookup only and never start an interactive flow) is handed to `provideTools` only; the
     * `describeNamespace` call receives the context without it and starts once `provideTools` is done,
     * whose outcome some plugins report in their namespace line. When `provideTools` was abandoned, it
     * may still be running meanwhile and that line may reflect the state before it.
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
                credentialProviderFactory.forPreview(namespaceId = namespaceId, userId = user.id)(authSettingName)
            }
        val toolContext = baseContext.copy(credentialProvider = credentialProvider)
        val (tools, error) = previewTools(plugin, config, toolContext)
        return IntegrationConfigToolPreview(
            integrationType = config.integrationType,
            configName = config.name,
            namespaceDescription = describeNamespace(plugin, config, baseContext),
            tools = tools,
            error = error,
        )
    }

    /**
     * The mapped tools, or an empty list plus the failure rendered as `ExceptionClass: message`: a call
     * past [IntegrationsProperties.previewProvideToolsTimeoutMs] is rendered as a [TimeoutException], a
     * call refused because every worker is busy as a [RejectedExecutionException].
     */
    private fun previewTools(
        plugin: ToolPlugin,
        config: IntegrationConfig,
        context: ToolContext,
    ): Pair<List<IntegrationConfigToolPreview.ToolPreview>, String?> {
        val timeoutMs = integrationsProperties.previewProvideToolsTimeoutMs
        val outcome =
            callPlugin(config, call = "provideTools", timeoutMs = timeoutMs) {
                plugin
                    .provideTools(config = config.parameters, configName = config.name, context = context)
                    .map { tool -> toPreview(tool, context) }
            }
        val noTools = emptyList<IntegrationConfigToolPreview.ToolPreview>()
        return when (outcome) {
            is PluginCallOutcome.Completed -> outcome.value to null
            is PluginCallOutcome.Failed -> noTools to describeFailure(outcome.cause)
            PluginCallOutcome.TimedOut ->
                noTools to describeFailure(TimeoutException("tools not built within $timeoutMs ms"))
            PluginCallOutcome.Rejected ->
                noTools to
                    describeFailure(
                        RejectedExecutionException("all preview workers are busy with earlier previews, retry later"),
                    )
        }
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
     * The plugin's namespace line, or null when it gives none, fails, does not answer within
     * [IntegrationsProperties.previewDescribeNamespaceTimeoutMs] or finds no free worker.
     */
    private fun describeNamespace(
        plugin: ToolPlugin,
        config: IntegrationConfig,
        context: ToolContext,
    ): String? {
        val outcome =
            callPlugin(
                config,
                call = "describeNamespace",
                timeoutMs = integrationsProperties.previewDescribeNamespaceTimeoutMs,
            ) {
                plugin.describeNamespace(config = config.parameters, configName = config.name, context = context)
            }
        return (outcome as? PluginCallOutcome.Completed)?.value
    }

    /**
     * Runs [block] on a preview worker and waits for it at most [timeoutMs] without interrupting it, or
     * refuses at once when no worker is free. Failures are logged by the worker itself, so a call that
     * fails after the preview gave up is still traced.
     */
    private fun <T> callPlugin(
        config: IntegrationConfig,
        call: String,
        timeoutMs: Long,
        block: suspend () -> T,
    ): PluginCallOutcome<T> {
        if (!workerPermits.tryAcquire()) {
            logger.warn { "[ToolPreview] $call ${describe(config)} refused: every preview worker is busy" }
            return PluginCallOutcome.Rejected
        }
        val abandoned = AtomicBoolean(false)
        val deferred =
            pluginCallScope.async {
                try {
                    PluginCallOutcome.Completed(block()).also {
                        if (abandoned.get()) {
                            logger.info {
                                "[ToolPreview] $call ${describe(config)} completed after the preview gave up"
                            }
                        }
                    }
                } catch (e: Exception) {
                    // The preview giving up cancels this call: that cancellation is not a plugin failure.
                    if (e is CancellationException && !isActive) throw e
                    logger.warn(e) {
                        "[ToolPreview] $call failed ${describe(config)}" +
                            if (abandoned.get()) " after the preview gave up" else ""
                    }
                    PluginCallOutcome.Failed(e)
                }
            }
        // On completion rather than in the block: a call cancelled before it started never runs its block.
        deferred.invokeOnCompletion { workerPermits.release() }
        val outcome = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        if (outcome != null) return outcome
        abandoned.set(true)
        deferred.cancel()
        logger.warn { "[ToolPreview] $call ${describe(config)} did not complete within $timeoutMs ms; abandoned" }
        return PluginCallOutcome.TimedOut
    }

    private fun describe(config: IntegrationConfig): String =
        "for integration '${config.name}' (type '${config.integrationType}')"

    /** `ExceptionClass: message`, shown verbatim by the UI; never renders a `null` literal. */
    private fun describeFailure(e: Exception): String {
        val type = e::class.simpleName ?: e::class.java.name
        val message = e.message ?: "no message"
        return "$type: $message"
    }

    /** Cancels the calls still queued or running; running plugin code is not interrupted. */
    @PreDestroy
    fun shutdown() {
        pluginCallScope.cancel()
    }

    /** How a plugin call ended from the preview's point of view. */
    private sealed interface PluginCallOutcome<out T> {
        data class Completed<T>(
            val value: T,
        ) : PluginCallOutcome<T>

        data class Failed(
            val cause: Exception,
        ) : PluginCallOutcome<Nothing>

        /** The bound elapsed; the call was abandoned and may still be running. */
        data object TimedOut : PluginCallOutcome<Nothing>

        /** Every worker was busy; the plugin was not called. */
        data object Rejected : PluginCallOutcome<Nothing>
    }

    companion object : KLogging()
}
