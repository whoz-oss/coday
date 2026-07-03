package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP resource for a fully-resolved agent definition.
 *
 * Produced by [AgentConfigController.getDefinition] from a
 * [io.whozoss.agentos.agent.ResolvedAgentDefinition]. Suitable for debugging
 * the effective runtime configuration of an agent config without starting a case.
 */
@Schema(name = "AgentDefinition")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentDefinitionResource(
    val agentConfigId: UUID,
    val name: String,
    /** Privileged namespace context block sent as a separate system message, before [instructions]. Null when no namespace context is available. */
    val systemPrompt: String?,
    /** Final system instructions sent to the LLM, including injected namespace/integration/user blocks. */
    val instructions: String?,
    val resolvedModelApiName: String,
    val resolvedProviderName: String,
    val tools: List<ToolSummary>,
    val advancedExecution: Boolean,
    val namespaceId: UUID,
    val userId: UUID?,
) {
    @Schema(name = "AgentDefinitionToolSummary")
    data class ToolSummary(
        val name: String,
        val description: String,
        val inputSchema: String,
    )
}
