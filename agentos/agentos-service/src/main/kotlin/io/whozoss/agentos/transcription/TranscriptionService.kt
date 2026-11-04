package io.whozoss.agentos.transcription

import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.TranscriptionModelFactory
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service

/** Raised when voice transcription cannot run: no platform WHISPER model, or an unsupported provider type. */
class TranscriptionUnavailableException(
    message: String,
) : RuntimeException(message)

/**
 * Speech-to-text (Whisper) as a platform capability.
 *
 * Enabled only when a **platform-level** [AiModel] (namespaceId == null AND userId == null) carries the
 * alias [WHISPER_ALIAS] (case-insensitive) and its [AiProvider] is of type OpenAI or vLLM. Models with that
 * alias at namespace or user level are ignored on purpose, so [AiModelService.findAiModel] (which prefers
 * the namespace scope) is not used.
 *
 * No usage or cost tracking.
 */
@Service
class TranscriptionService(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val transcriptionModelFactory: TranscriptionModelFactory,
) {
    /** True when a platform WHISPER model exists on an OpenAI or vLLM provider. */
    fun isAvailable(): Boolean = findPlatformWhisperModel()?.let { supported(aiProviderService.getById(it.aiProviderId)) } ?: false

    /**
     * Transcribe [audio] with the platform WHISPER model.
     *
     * @param mimeType audio MIME type (e.g. `audio/webm`), used to give the upload a file extension,
     *   which the OpenAI API relies on to detect the format.
     * @param language optional ISO-639-1 code; null (or blank) lets Whisper auto-detect.
     * @throws TranscriptionUnavailableException when no usable platform WHISPER model is configured.
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
        if (!supported(provider)) {
            throw TranscriptionUnavailableException(
                "Voice transcription is unavailable: provider '${provider.name}' has type ${provider.apiType}, " +
                    "only ${AiApiType.OpenAI} and ${AiApiType.vLLM} are supported",
            )
        }

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
                override fun getFilename(): String = "audio.${extensionFor(mimeType)}"
            }
        return transcriptionModel.call(AudioTranscriptionPrompt(resource)).result.output
    }

    private fun findPlatformWhisperModel(): AiModel? =
        aiModelService
            .findPlatformLevel()
            .filter { it.namespaceId == null && it.userId == null && it.alias.equals(WHISPER_ALIAS, ignoreCase = true) }
            .maxByOrNull { it.priority }

    private fun supported(provider: AiProvider): Boolean = provider.apiType == AiApiType.OpenAI || provider.apiType == AiApiType.vLLM

    private fun extensionFor(mimeType: String): String =
        when (mimeType.substringBefore(';').trim().lowercase()) {
            "audio/webm" -> "webm"
            "audio/ogg" -> "ogg"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/flac" -> "flac"
            else -> "webm"
        }

    companion object {
        const val WHISPER_ALIAS = "WHISPER"
    }
}
