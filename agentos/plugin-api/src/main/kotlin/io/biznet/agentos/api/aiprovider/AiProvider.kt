package io.biznet.agentos.api.aiprovider

data class AiProvider(
    val id: String,
    val name: String,
    val apiType: ApiType,
    val baseUrl: String,
    val description: String? = null,
    val defaultApiKey: String? = null,
    val baseModel: String? = null,
    val temperature: Double = 1.0,
)

enum class ApiType {
    OpenAI, Anthropic, Gemini
}
