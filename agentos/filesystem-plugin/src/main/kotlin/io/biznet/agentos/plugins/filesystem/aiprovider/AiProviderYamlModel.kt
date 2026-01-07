package io.biznet.agentos.plugins.filesystem.aiprovider

data class AiProviderYamlModel(
    val name: String,
    val apiType: String,
    val baseUrl: String? = null,
    val description: String? = null,
    val defaultApiKey: String? = null,
    val baseModel: String? = null,
    val temperature: Double?,
)
