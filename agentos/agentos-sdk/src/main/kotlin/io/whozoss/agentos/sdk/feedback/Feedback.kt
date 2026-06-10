package io.whozoss.agentos.sdk.feedback

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * User feedback attached to a [io.whozoss.agentos.sdk.caseEvent.CaseEvent].
 *
 * Feedback is stored as a separate entity rather than being embedded inside the
 * case event, keeping the event log immutable. Any event subtype can be the target
 * of feedback via [caseEventId].
 *
 * [positive] captures the thumbs-up / thumbs-down sentiment.
 * [type] is an optional free-form category label (e.g. "WRONG_ANSWER", "HELPFUL").
 * [comment] is an optional free-text note from the user.
 *
 * [caseId] and [namespaceId] are denormalized scalars that allow efficient
 * batch queries ("all feedback for a case", "all feedback in a namespace")
 * without graph traversal.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is required because [Entity] exposes
 * a computed `id` property that Jackson serialises on write but cannot find in the
 * constructor on read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Feedback(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val caseId: UUID,
    /** The [io.whozoss.agentos.sdk.caseEvent.CaseEvent] this feedback targets. */
    val caseEventId: UUID,
    val positive: Boolean,
    val type: String? = null,
    val comment: String? = null,
) : Entity
