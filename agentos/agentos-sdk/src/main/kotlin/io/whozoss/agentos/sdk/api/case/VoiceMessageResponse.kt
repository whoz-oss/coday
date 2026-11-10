package io.whozoss.agentos.sdk.api.case

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Response body of `POST /api/cases/{caseId}/voice-messages`.
 *
 * @property transcript The text Whisper produced from the recording; it is posted to the case as the text
 *   of the message and is what the agent reads.
 * @property path Where the recording was stored, relative to the case exchange root (the `path` of the
 *   case files endpoints, e.g. `GET /api/cases/{caseId}/files/download?path=`).
 */
@Schema(name = "VoiceMessageResponse")
data class VoiceMessageResponse(
    val transcript: String,
    val path: String,
)
