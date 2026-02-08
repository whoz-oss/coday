package io.whozoss.agentos.sdk.aiProvider

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata

/**
 * Data structure defining an agent's configuration.
 *
 * This is a pure data model that describes what an agent is, separate from how it executes.
 * Similar to AgentDefinition in Coday TypeScript, this allows agents to be defined
 * declaratively (e.g., from YAML/JSON) and shared between different implementations
 * (AgentSimple, AgentAdvanced).
 *
 * @property id Unique identifier for the agent
 * @property name The agent's display name
 * @property description Description of the agent for users and other agents
 * @property instructions System instructions/prompt for the agent (optional)
 * @property provider The AI provider to use (null = use default from configuration)
 * @property modelName Explicit model name or alias (e.g., "gpt-4", "claude-3-opus")
 * @property temperature Temperature parameter for LLM (0.0 to 2.0, where 0.2 is deterministic, 0.8 is creative)
 * @property maxOutputTokens Maximum number of output tokens (overrides model's default if specified)
 */
data class AiModel(
    override val metadata: EntityMetadata = EntityMetadata(),
    val name: String,
    val description: String,
    val instructions: String? = null,
    val provider: AiProvider? = null,
    val modelName: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
) : Entity
