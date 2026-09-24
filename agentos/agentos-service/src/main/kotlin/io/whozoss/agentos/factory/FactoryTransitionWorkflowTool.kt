package io.whozoss.agentos.factory

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import okhttp3.OkHttpClient

/** Compatibility alias for the ProductEngineer contract. */
class FactoryTransitionWorkflowTool(
    baseUrl: String,
    httpClient: OkHttpClient,
    objectMapper: ObjectMapper,
    runtimeId: String,
) : StandardTool<FactoryRequestTransitionTool.Input> {
    private val delegate = FactoryRequestTransitionTool(baseUrl, httpClient, objectMapper, runtimeId)
    override val name = "FACTORY__transition_workflow"
    override val description = delegate.description
    override val version = delegate.version
    override val paramType = delegate.paramType
    override val inputSchema = delegate.inputSchema
    override suspend fun execute(input: FactoryRequestTransitionTool.Input?, context: ToolContext): ToolExecutionResult =
        delegate.execute(input, context)
}
