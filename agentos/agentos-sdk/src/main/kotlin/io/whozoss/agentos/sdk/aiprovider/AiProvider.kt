package io.whozoss.agentos.sdk.aiprovider

data class AiProvider(
    val id: String,
    val name: String,
    val apiType: ApiType,
    val baseUrl: String?,
    val description: String? = null,
    val defaultApiKey: String? = null,
    val baseModel: String? = null,
    val temperature: Double = 1.0,
    val maxTokens: Int? = null,
)

enum class ApiType {
    OpenAI,
    Anthropic,
    Gemini,
}
