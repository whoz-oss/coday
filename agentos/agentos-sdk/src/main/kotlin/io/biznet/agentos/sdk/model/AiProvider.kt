package io.biznet.agentos.sdk.model

data class AiProvider(
    val id: String,
    val name: String,
    val apiType: AiApiType,
    val baseUrl: String,
    val description: String? = null,
    val defaultApiKey: String? = null,
    val baseModel: String? = null,
    val temperature: Double = 1.0,
)
