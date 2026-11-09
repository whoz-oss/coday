package io.whozoss.agentos.transcription

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.exchange.ExchangeRootResolver
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exchange.FileExistsException
import io.whozoss.agentos.exchange.InvalidExchangePathException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.case.VoiceMessageResponse
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.time.measureTimedValue

/**
 * Voice messages: a recording is transcribed with Whisper, stored in the case exchange, and posted to the
 * case as one user message made of the audio and its transcript.
 *
 * The access check on the case itself (Case WRITE) is declared by [VoiceMessageController]. This service adds
 * two rules: the caller must hold WRITE on the namespace of the case (see [requireNamespaceWrite]), and the
 * owner-access rule applies when the exchange directory is shared with another case (see [storeAudio]).
 */
@Service
class VoiceMessageService(
    private val transcriptionService: TranscriptionService,
    private val caseService: CaseService,
    private val exchangeRootResolver: ExchangeRootResolver,
    private val exchangeStorageService: ExchangeStorageService,
    private val exchangeCapabilityService: ExchangeCapabilityService,
    private val userService: UserService,
) {
    /** Whether voice messages can be transcribed here. */
    fun isAvailable(): Boolean = transcriptionService.isAvailable()

    /**
     * Transcribe, store, post.
     *
     * The caller must hold WRITE on the namespace of the case; this is checked first, before anything is
     * transcribed, stored or posted, and before the availability of the feature is revealed.
     *
     * The transcription comes first: when it fails or comes back empty, nothing is stored and nothing is
     * posted. The message carries the [MessageContent.Audio] first and the [MessageContent.Text] transcript
     * second; only the transcript reaches the agent.
     *
     * @param contentType the declared MIME type of the upload, possibly with parameters (`audio/webm;codecs=opus`)
     * @param bytes the recording
     */
    fun postVoiceMessage(
        caseId: UUID,
        contentType: String?,
        bytes: ByteArray,
        answerToEventId: UUID?,
        durationMs: Long?,
    ): VoiceMessageResponse {
        val case = caseService.findById(caseId) ?: throw ResourceNotFoundException("Case not found: $caseId")
        val user = userService.getCurrentUser()
        val userId = user.id.toString()
        requireNamespaceWrite(userId, case)

        if (!isAvailable()) {
            throw ConflictException("Voice messages are not available on this instance")
        }
        val mimeType = validatedMimeType(contentType, bytes)

        val transcript = transcribe(caseId, bytes, mimeType, user.preferredLanguage)

        val relativePath = voiceMessagePath(mimeType)
        storeAudio(case, userId, relativePath, bytes)

        val actor = Actor(id = user.metadata.id.toString(), displayName = user.displayName(), role = ActorRole.USER)
        caseService.addMessage(
            caseId = caseId,
            actor = actor,
            content =
                listOf(
                    MessageContent.Audio(path = relativePath, mimeType = mimeType, durationMs = durationMs?.takeIf { it >= 0 }),
                    MessageContent.Text(transcript),
                ),
            answerToEventId = answerToEventId,
            sessionContext = null,
        )
        logger.info { "Voice message posted to case $caseId: stored at '$relativePath'" }
        return VoiceMessageResponse(transcript = transcript, path = relativePath)
    }

    /** A voice message is a write into the case, made on behalf of the namespace: WRITE on the namespace is required. */
    private fun requireNamespaceWrite(
        userId: String,
        case: Case,
    ) {
        if (!exchangeCapabilityService.canWrite(userId, EntityType.NAMESPACE, case.namespaceId.toString())) {
            throw AccessDeniedException("Voice messages require write access to the namespace")
        }
    }

    /** The base MIME type of the upload, after checking the type is an accepted audio type and the size fits. */
    private fun validatedMimeType(
        contentType: String?,
        bytes: ByteArray,
    ): String {
        val mimeType =
            AudioMimeTypes.baseType(contentType)?.takeIf { it in AudioMimeTypes.ALLOWED }
                ?: throw BadRequestException("Unsupported audio type: ${contentType ?: "none"}")
        if (bytes.isEmpty()) throw BadRequestException("The audio recording is empty")
        if (bytes.size > MAX_AUDIO_BYTES) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The audio recording exceeds the 25 MB limit")
        }
        return mimeType
    }

    private fun transcribe(
        caseId: UUID,
        bytes: ByteArray,
        mimeType: String,
        preferredLanguage: String?,
    ): String {
        val (transcript, elapsed) =
            measureTimedValue {
                try {
                    transcriptionService.transcribe(bytes, mimeType, whisperLanguage(preferredLanguage))
                } catch (e: TranscriptionUnavailableException) {
                    throw ConflictException("Voice messages are not available on this instance", e)
                } catch (e: Exception) {
                    // The provider's message can carry request details: log it, answer with a generic one.
                    logger.warn(e) { "Transcription failed for case $caseId" }
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "The recording could not be transcribed")
                }
            }
        logger.info { "Transcribed voice message for case $caseId: $mimeType, ${bytes.size} bytes, ${elapsed.inWholeMilliseconds}ms" }
        if (transcript.isBlank()) {
            throw UnprocessableEntityException("No speech was detected in the recording")
        }
        return transcript
    }

    /**
     * Store the recording in the case exchange, the way [io.whozoss.agentos.exchange.ExchangeController.uploadCaseFile]
     * does: through the resolver's mutation hook, with the owner-access rule when the directory is shared
     * with another case. The path is server-generated, so the upload extension allow-list does not apply.
     */
    private fun storeAudio(
        case: Case,
        userId: String,
        relativePath: String,
        bytes: ByteArray,
    ) {
        val caseId = case.id
        exchangeRootResolver.withFileMutation(case) { root ->
            if (root.ownerCaseId != caseId) {
                exchangeCapabilityService.requireCaseAccess(userId, caseId, root, Action.WRITE)
            }
            val directory = root.requireUsable()
            try {
                exchangeStorageService.writeNew(directory, relativePath, bytes, ExchangeScope.CASE)
            } catch (e: FileExistsException) {
                throw ConflictException("A voice message with this name already exists, retry", e)
            } catch (e: InvalidExchangePathException) {
                throw BadRequestException(e.message ?: "Invalid path")
            } catch (e: IOException) {
                // Generic message on purpose: the raw one carries the absolute server path.
                logger.warn(e) { "Could not store a voice message for case $caseId" }
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The voice message could not be stored")
            }
        }
    }

    /** `voice-messages/voice-<yyyyMMdd-HHmmss>-<short-uuid>.<ext>`, unique and independent of anything the client sent. */
    private fun voiceMessagePath(mimeType: String): String {
        val timestamp = TIMESTAMP_FORMAT.format(Instant.now())
        val shortId = UUID.randomUUID().toString().take(8)
        return "$DIRECTORY/voice-$timestamp-$shortId.${AudioMimeTypes.extensionFor(mimeType) ?: "webm"}"
    }

    /**
     * Whisper takes an ISO-639-1 code, the user's preference is a BCP 47 tag (`fr`, `en-US`): keep the
     * primary subtag when it is two letters, otherwise let Whisper detect the language.
     */
    private fun whisperLanguage(preferredLanguage: String?): String? =
        preferredLanguage
            ?.substringBefore('-')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.length == 2 && it.all(Char::isLetter) }

    companion object : KLogging() {
        /** Whisper's upload limit. */
        const val MAX_AUDIO_BYTES: Long = 25L * 1024 * 1024
        private const val DIRECTORY = "voice-messages"
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
