package io.biznet.agentos.orchestration

/**
 * Data structure defining an agent's configuration.
 *
 * This is a pure data model that describes what an agent is, separate from how it executes.
 * Similar to AgentDefinition in Coday TypeScript, this allows agents to be defined
 * declaratively (e.g., from YAML/JSON) and shared between different implementations
 * (AgentSimple, AgentAdvanced).
 *
 * @property name The agent's display name
 * @property description Description of the agent for users and other agents
 * @property instructions System instructions/prompt for the agent (optional)
 * @property aiProvider The AI provider to use (e.g., "openai", "anthropic")
 * @property modelName Explicit model name or alias (e.g., "gpt-4", "claude-3-opus")
 * @property temperature Temperature parameter for LLM (0.0 to 2.0, where 0.2 is deterministic, 0.8 is creative)
 * @property maxOutputTokens Maximum number of output tokens (overrides model's default if specified)
 */
data class AgentModel(
    val name: String,
    val description: String,
    val instructions: String? = null,
    val aiProvider: String? = null,
    val modelName: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)
