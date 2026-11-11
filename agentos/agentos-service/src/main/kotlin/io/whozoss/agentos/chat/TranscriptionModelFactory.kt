package io.whozoss.agentos.chat

import org.springframework.ai.audio.transcription.TranscriptionModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions
import org.springframework.ai.openai.api.OpenAiAudioApi
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap

/**
 * Builds Spring AI audio transcription models (OpenAI Whisper API, also served by vLLM).
 *
 * Sibling of [ChatModelFactory]: the caller (see [io.whozoss.agentos.transcription.TranscriptionService])
 * resolves the model/provider and passes the connectivity details, this factory only wires Spring AI.
 *
 * TODO: remove this specific implementation once the project is on Spring AI 2, which makes it unnecessary
 *  (review of PR #1578).
 */
@Component
class TranscriptionModelFactory {
    /**
     * @param language optional ISO-639-1 code (e.g. "fr"); null lets the model auto-detect the language.
     */
    fun createTranscriptionModel(
        baseUrl: String?,
        apiKey: String?,
        modelName: String,
        language: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): TranscriptionModel {
        val apiBuilder =
            OpenAiAudioApi
                .builder()
                .baseUrl(baseUrl ?: OPENAI_DEFAULT_BASE_URL)
                .apiKey(apiKey ?: "")
        if (headers.isNotEmpty()) {
            apiBuilder.headers(LinkedMultiValueMap<String, String>(headers.mapValues { (_, value) -> listOf(value) }))
        }

        val optionsBuilder =
            OpenAiAudioTranscriptionOptions
                .builder()
                .model(modelName)
                // JSON: Spring AI parses the `text` field itself. TEXT returns the raw body, which OpenAI-compatible
                // routers (e.g. Requesty) may still answer as a JSON document.
                .responseFormat(OpenAiAudioApi.TranscriptResponseFormat.JSON)
        if (language != null) {
            optionsBuilder.language(language)
        }

        return OpenAiAudioTranscriptionModel(apiBuilder.build(), optionsBuilder.build())
    }

    companion object {
        private const val OPENAI_DEFAULT_BASE_URL = "https://api.openai.com"
    }
}
