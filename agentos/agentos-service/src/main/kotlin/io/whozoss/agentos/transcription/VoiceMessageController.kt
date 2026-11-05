package io.whozoss.agentos.transcription

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
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
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.case.VoiceMessageCapabilityResponse
import io.whozoss.agentos.sdk.api.case.VoiceMessageResponse
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.util.UUID

/**
 * Voice messages: a recording is transcribed with Whisper, stored in the case exchange, and posted to the
 * case as one user message made of the audio and its transcript.
 *
 * A standalone controller, and deliberately not part of `CaseApi`: adding an abstract method to an
 * interface that external consumers implement would break them at startup (same reasoning as the
 * additive endpoints of [io.whozoss.agentos.exchange.ExchangeController]).
 */
@RestController
class VoiceMessageController(
    private val transcriptionService: TranscriptionService,
    private val caseService: CaseService,
    private val exchangeRootResolver: ExchangeRootResolver,
    private val exchangeStorageService: ExchangeStorageService,
    private val exchangeCapabilityService: ExchangeCapabilityService,
    private val userService: UserService,
) {
    /** GET /api/voice-messages/capability: whether voice messages can be transcribed here. Nothing else is exposed. */
    @GetMapping("/api/voice-messages/capability", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    fun capability(): VoiceMessageCapabilityResponse = VoiceMessageCapabilityResponse(available = transcriptionService.isAvailable())

    /**
     * POST /api/cases/{caseId}/voice-messages: transcribe, store, post.
     *
     * The transcription comes first: when it fails or comes back empty, nothing is stored and nothing is
     * posted. The message carries the [MessageContent.Audio] first and the [MessageContent.Text] transcript
     * second; only the transcript reaches the agent.
     */
    @SwaggerRequestBody(
        content = [
            Content(
                mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                schema = Schema(type = "object", requiredProperties = ["audio"]),
                schemaProperties = [
                    SchemaProperty(name = "audio", schema = Schema(type = "string", format = "binary")),
                    SchemaProperty(name = "answerToEventId", schema = Schema(type = "string", format = "uuid")),
                    SchemaProperty(name = "durationMs", schema = Schema(type = "integer", format = "int64")),
                ],
            ),
        ],
    )
    @PostMapping(
        "/api/cases/{caseId}/voice-messages",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun postVoiceMessage(
        @PathVariable caseId: UUID,
        @RequestParam("audio") audio: MultipartFile,
        @RequestParam("answerToEventId", required = false) answerToEventId: UUID?,
        @RequestParam("durationMs", required = false) durationMs: Long?,
    ): VoiceMessageResponse {
        if (!transcriptionService.isAvailable()) {
            throw ConflictException("Voice messages are not available on this instance")
        }
        val mimeType = validatedMimeType(audio)
        val bytes = audio.bytes

        val user = userService.getCurrentUser()
        val transcript = transcribe(caseId, bytes, mimeType, user.preferredLanguage)

        val relativePath = voiceMessagePath(mimeType)
        storeAudio(caseId, relativePath, bytes)

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

    /** The base MIME type of the upload, after checking the type is an accepted audio type and the size fits. */
    private fun validatedMimeType(audio: MultipartFile): String {
        val mimeType =
            AudioMimeTypes.baseType(audio.contentType)?.takeIf { it in AudioMimeTypes.ALLOWED }
                ?: throw BadRequestException("Unsupported audio type: ${audio.contentType ?: "none"}")
        if (audio.isEmpty) throw BadRequestException("The audio recording is empty")
        if (audio.size > MAX_AUDIO_BYTES) {
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
        val startedAtNanos = System.nanoTime()
        val transcript =
            try {
                transcriptionService.transcribe(bytes, mimeType, whisperLanguage(preferredLanguage))
            } catch (e: TranscriptionUnavailableException) {
                throw ConflictException("Voice messages are not available on this instance", e)
            } catch (e: Exception) {
                // The provider's message can carry request details: log it, answer with a generic one.
                logger.warn(e) { "Transcription failed for case $caseId" }
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "The recording could not be transcribed")
            }
        logger.info {
            "Transcribed voice message for case $caseId: $mimeType, ${bytes.size} bytes, " +
                "${(System.nanoTime() - startedAtNanos) / 1_000_000}ms"
        }
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
        caseId: UUID,
        relativePath: String,
        bytes: ByteArray,
    ) {
        val case = caseService.findById(caseId) ?: throw ResourceNotFoundException("Case not found: $caseId")
        exchangeRootResolver.withFileMutation(case) { root ->
            if (root.ownerCaseId != caseId) {
                exchangeCapabilityService.requireCaseAccess(
                    userService.getCurrentUser().id.toString(),
                    caseId,
                    root,
                    Action.WRITE,
                )
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
