package io.whozoss.agentos.tool

import io.whozoss.agentos.auth.ConnectionPolicyRegistry
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/** Captures the host binding even when a direct invocation builds a fresh caller context. */
internal class GuardedConnectionTool<T>(private val delegate: StandardTool<T>, private val bound: ToolContext) : StandardTool<T> by delegate {
    private fun checked(context: ToolContext): ToolContext {
        check(context.namespaceId == bound.namespaceId && context.userId == bound.userId) { ConnectionPolicyRegistry.DENIED }
        bound.connectionCapability!!.requireActive()
        return context.copy(credentialProvider = bound.credentialProvider, authSettingName = bound.authSettingName,
            connectionCapability = bound.connectionCapability)
    }
    override suspend fun execute(input: T?, context: ToolContext): ToolExecutionResult =
        guarded { delegate.execute(input, checked(context)) }
    override suspend fun executeWithJson(json: String?, context: ToolContext): ToolExecutionResult =
        guarded { delegate.executeWithJson(json, checked(context)) }
    override suspend fun enrich(phaseIndex: Int, phaseParametersJson: String, context: ToolContext): io.whozoss.agentos.sdk.tool.EnrichmentResult =
        delegate.enrich(phaseIndex, phaseParametersJson, checked(context))

    private suspend fun guarded(call: suspend () -> ToolExecutionResult): ToolExecutionResult = try { call() } catch (e: IllegalStateException) {
        if (e.message?.startsWith(ConnectionPolicyRegistry.DENIED) != true) throw e
        ToolExecutionResult(output = ConnectionPolicyRegistry.DENIED, success = false,
            errorType = ConnectionPolicyRegistry.DENIED, errorMessage = ConnectionPolicyRegistry.DENIED)
    }
}
