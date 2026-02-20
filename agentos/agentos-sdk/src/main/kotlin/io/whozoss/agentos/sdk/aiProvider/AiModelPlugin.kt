package io.whozoss.agentos.sdk.aiProvider

import org.pf4j.ExtensionPoint

/**
 * Extension point for providing AI model configurations.
 *
 * Plugins implement this interface to contribute AiModel definitions to AgentOS.
 * Each model references an AiProvider by name and can override provider-level defaults.
 *
 * ## Usage Example
 *
 * ```kotlin
 * @Extension
 * class MyAiModelProvider : AiModelPlugin {
 *     override fun getPluginId(): String = "my-ai-models"
 *
 *     override fun getAiModels(): List<AiModel> = listOf(
 *         AiModel(
 *             name = "gpt-4o",
 *             description = "OpenAI GPT-4o model",
 *             modelName = "gpt-4o",
 *             providerName = "openai",
 *             temperature = 0.7,
 *         )
 *     )
 * }
 * ```
 */
interface AiModelPlugin : ExtensionPoint {
    /**
     * Unique identifier for this plugin.
     */
    fun getPluginId(): String

    /**
     * List of AI models provided by this plugin.
     */
    fun getAiModels(): List<AiModel>

    fun getDescription(): String = ""

    fun initialize() {}

    fun destroy() {}
}
