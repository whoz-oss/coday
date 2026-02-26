package io.whozoss.agentos.plugin.filesystem.aimodel

/**
 * YAML model for AI model configuration files.
 *
 * Example YAML:
 * ```yaml
 * name: gpt-4o
 * description: OpenAI GPT-4o for general tasks
 * modelName: gpt-4o
 * providerName: openai
 * temperature: 0.7
 * maxTokens: 4096
 * instructions: |
 *   You are a helpful assistant.
 * ```
 */
data class AiModelYamlModel(
    val name: String,
    val description: String,
    val modelName: String,
    val providerName: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val instructions: String? = null,
)
