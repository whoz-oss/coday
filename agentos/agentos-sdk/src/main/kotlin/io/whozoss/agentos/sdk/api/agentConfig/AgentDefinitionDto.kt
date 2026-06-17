package io.whozoss.agentos.sdk.api.agentConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * HTTP response for `GET /api/agent-configs/{id}/definition`.
 *
 * Represents a fully-resolved agent definition: effective instructions (with namespace /
 * integration / user context injected), resolved model and provider, and the list of tools
 * that would be made available to the agent.
 *
 * Useful for debugging agent configurations without starting a case.
 *
 * @property agentConfigId The ID of the [AgentConfigDto] this definition was resolved from.
 * @property name The agent's display name.
 * @property systemPrompt Privileged namespace context block sent as a separate system message,
 *   before [instructions]. Null when no namespace context is available.
 * @property instructions Final system instructions sent to the LLM, including injected
 *   namespace / integration / user context blocks.
 * @property resolvedModelApiName The actual API model name sent to the provider.
 * @property resolvedProviderName The name of the resolved AI provider.
 * @property tools Summary of tools that would be made available to this agent.
 * @property advancedExecution Whether the agent uses the advanced multi-step execution engine.
 * @property namespaceId The namespace this agent belongs to.
 * @property userId The user whose overlay was applied, or null for namespace-only resolution.
 */
@Schema(name = "AgentDefinition")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentDefinitionDto(
    val agentConfigId: UUID,
    val name: String,
    val systemPrompt: String?,
    val instructions: String?,
    val resolvedModelApiName: String,
    val resolvedProviderName: String,
    val tools: List<ToolSummary>,
    val advancedExecution: Boolean,
    val namespaceId: UUID,
    val userId: UUID?,
) {
    /**
     * Summary of a single tool available to the agent.
     */
    @Schema(name = "AgentDefinitionToolSummary")
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ToolSummary(
        val name: String,
        val description: String,
        val inputSchema: String,
    )
}
