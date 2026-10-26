package io.whozoss.agentos.sdk.aiProvider

import io.swagger.v3.oas.annotations.media.Schema

/**
 * How much a model reasons before answering, on one scale shared by every [AiApiType].
 *
 * Null on [AiModel.reasoningEffort] keeps the provider default. The service translates a level
 * into each provider's native setting: verbatim for OpenAI and routers such as Requesty, where the
 * provider rejects a level the model does not support, and to the nearest native level where the
 * provider's scale is coarser (vLLM, Ollama, Gemini).
 *
 * There is no `minimal` level: the Requesty router rejects it for every model.
 *
 * Published as one shared OpenAPI schema, so the generated clients have a single enum.
 */
@Schema(enumAsRef = true)
enum class ReasoningEffort {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
}
