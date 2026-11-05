package io.whozoss.agentos.transcription

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.TranscriptionModelFactory
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service

/** Raised when voice transcription cannot run: no platform WHISPER model is configured. */
class TranscriptionUnavailableException(
    message: String,
) : RuntimeException(message)

/**
 * Speech-to-text (Whisper) as a platform capability.
 *
 * Enabled only when a **platform-level** [AiModel] (namespaceId == null AND userId == null) carries the
 * alias [WHISPER_ALIAS] (case-insensitive); the highest priority wins. Models with that alias at namespace
 * or user level are ignored on purpose, so [AiModelService.findAiModel] (which prefers the namespace scope)
 * is not used.
 *
 * The provider's API type is deliberately not checked: the capability depends on the alias alone. The call
 * is an OpenAI-style transcription request (the provider's base URL, API key and headers); a provider that
 * cannot answer it makes the call fail, which the caller reports as a transcription failure.
 *
 * No usage or cost tracking.
 */
@Service
class TranscriptionService(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val transcriptionModelFactory: TranscriptionModelFactory,
) {
    /** True when a platform-level model with the WHISPER alias exists. */
    fun isAvailable(): Boolean = findPlatformWhisperModel() != null

    /**
     * Transcribe [audio] with the platform WHISPER model.
     *
     * @param mimeType audio MIME type (e.g. `audio/webm`), used to give the upload a file extension,
     *   which the OpenAI API relies on to detect the format.
     * @param language optional ISO-639-1 code; null (or blank) lets Whisper auto-detect.
     * @throws TranscriptionUnavailableException when no platform WHISPER model is configured.
     */
    fun transcribe(
        audio: ByteArray,
        mimeType: String,
        language: String?,
    ): String {
        val model =
            findPlatformWhisperModel()
                ?: throw TranscriptionUnavailableException(
                    "Voice transcription is unavailable: no platform-level AI model with alias '$WHISPER_ALIAS' is configured",
                )
        val provider = aiProviderService.getById(model.aiProviderId)

        val transcriptionModel =
            transcriptionModelFactory.createTranscriptionModel(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                modelName = model.apiModelName,
                language = language?.takeIf { it.isNotBlank() },
                headers = provider.headers,
            )
        val resource =
            object : ByteArrayResource(audio) {
                override fun getFilename(): String = "audio.${AudioMimeTypes.extensionFor(mimeType) ?: "webm"}"
            }
        return normalizeTranscript(transcriptionModel.call(AudioTranscriptionPrompt(resource)).result.output)
    }

    /**
     * Some OpenAI-compatible routers answer a JSON document (`{"text": "..."}`) whatever the requested format.
     * Trim the output and, when it is a JSON object with a string `text` field, keep only that field.
     * Anything else is kept as is. The transcript is user content and is never logged.
     */
    private fun normalizeTranscript(output: String?): String {
        val trimmed = output?.trim().orEmpty()
        if (!trimmed.startsWith("{")) return trimmed
        val text =
            try {
                (jsonMapper.readTree(trimmed) as? ObjectNode)?.get("text")?.takeIf { it.isTextual }?.asText()
            } catch (_: JsonProcessingException) {
                null
            }
        return text?.trim() ?: trimmed
    }

    private fun findPlatformWhisperModel(): AiModel? =
        aiModelService
            .findPlatformLevel()
            .filter { it.namespaceId == null && it.userId == null && it.alias.equals(WHISPER_ALIAS, ignoreCase = true) }
            .maxByOrNull { it.priority }

    companion object {
        const val WHISPER_ALIAS = "WHISPER"
        private val jsonMapper = ObjectMapper()
    }
}
