package io.whozoss.agentos.sdk.aiProvider

/**
 * How much a model reasons before answering, on one scale shared by every [AiApiType].
 *
 * Null on [AiModel.reasoningEffort] keeps the provider default. The service translates a level
 * into each provider's native setting: verbatim where the API takes a string (OpenAI-compatible
 * APIs, where the provider rejects a level the model does not support), and to the nearest
 * native level where the provider's scale is coarser (Ollama, Gemini).
 *
 * There is no `minimal` level: the Requesty router rejects it for every model.
 */
enum class ReasoningEffort {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
}
