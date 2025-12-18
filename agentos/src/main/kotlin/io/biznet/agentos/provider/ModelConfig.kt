package io.biznet.agentos.provider

/**
 * Configuration for selecting and configuring an AI model.
 *
 * @property providerId The ID of the AI provider (e.g., "anthropic", "openai", "vllm")
 * @property apiKey Optional API key override. If null, uses the provider's default configuration
 * @property model Optional model name override. If null, uses the provider's default model
 */
data class ModelConfig(
    val providerId: String,
    val apiKey: String? = null,
    val model: String? = null
)
