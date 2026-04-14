package io.whozoss.agentos.plugin.filesystem.aiprovider

data class AiProviderYamlModel(
    val name: String,
    val apiType: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
)
