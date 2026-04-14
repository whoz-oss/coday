package io.whozoss.agentos.plugin.filesystem.aimodel

/**
 * YAML model for AI model configuration files.
 *
 * [providerName] must match the [name] field of an AiProvider YAML loaded by
 * [FilesystemAIProviderProvider] — the UUID is derived deterministically from that
 * name so no persistent store is needed.
 *
 * Example YAML:
 * ```yaml
 * apiName: claude-haiku-4-5
 * providerName: anthropic
 * alias: SMALL
 * priority: 5
 * temperature: 0.3
 * maxTokens: 4096
 * ```
 */
data class AiModelYamlModel(
    val apiName: String,
    val providerName: String,
    val alias: String? = null,
    val priority: Int = 0,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)
