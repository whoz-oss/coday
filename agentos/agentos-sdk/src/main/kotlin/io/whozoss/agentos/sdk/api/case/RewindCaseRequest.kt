package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * Request body for `POST /api/cases/{caseId}/rewind`.
 *
 * [eventId] is the id of the user [io.whozoss.agentos.sdk.caseEvent.MessageEvent] to rewind to:
 * that message and every event after it are removed from the case.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RewindCaseRequest(
    val eventId: UUID,
)
