package io.whozoss.agentos.sdk.aiProvider

enum class AiApiType {
    OpenAI,

    /**
     * OpenAI over the Responses API (`/v1/responses`) instead of Chat Completions. Required for
     * function tools on models that refuse them over Chat Completions while reasoning, such as GPT-6.
     */
    OpenAIResponses,
    Anthropic,
    Gemini,
    vLLM,
    Ollama,
}
