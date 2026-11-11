package io.whozoss.agentos.transcription

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.whozoss.agentos.sdk.api.case.VoiceMessageCapabilityResponse
import io.whozoss.agentos.sdk.api.case.VoiceMessageResponse
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * REST entry point of the voice messages: a recording is transcribed with Whisper, stored in the case exchange,
 * and posted to the case as one user message made of the audio and its transcript. The work is done by
 * [VoiceMessageService]; this class only declares the routes and the access check.
 *
 * A standalone controller, and deliberately not part of `CaseApi`: adding an abstract method to an
 * interface that external consumers implement would break them at startup (same reasoning as the
 * additive endpoints of [io.whozoss.agentos.exchange.ExchangeController]).
 */
@RestController
class VoiceMessageController(
    private val voiceMessageService: VoiceMessageService,
) {
    /** GET /api/voice-messages/capability: whether voice messages can be transcribed here. Nothing else is exposed. */
    @GetMapping("/api/voice-messages/capability", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    fun capability(): VoiceMessageCapabilityResponse = VoiceMessageCapabilityResponse(available = voiceMessageService.isAvailable())

    /** POST /api/cases/{caseId}/voice-messages: transcribe, store, post (see [VoiceMessageService.postVoiceMessage]). */
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
    ): VoiceMessageResponse =
        voiceMessageService.postVoiceMessage(caseId, audio.contentType, audio.bytes, answerToEventId, durationMs)
}
