package io.whozoss.agentos.plugin.filesystem.aiprovider

data class AiProviderYamlModel(
    val name: String,
    val apiType: String,
    val baseUrl: String,
    val description: String? = null,
    val defaultApiKey: String? = null,
    val baseModel: String? = null,
    val temperature: Double?,
    val maxTokens: Int? = null,
)
