package io.whozoss.agentos.sdk.schedule

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/**
 * Terminal condition for a recurring [IntervalSchedule].
 *
 * Two variants:
 * - [Occurrences] — stop after N executions
 * - [EndTimestamp] — stop after a specific point in time
 *
 * Jackson discriminator uses the `type` property so the sealed hierarchy
 * round-trips cleanly through JSON without extra configuration at the call site.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = EndCondition.Occurrences::class, name = "occurrences"),
    JsonSubTypes.Type(value = EndCondition.EndTimestamp::class, name = "endTimestamp"),
)
sealed class EndCondition {
    /** Stop after [count] successful executions. */
    data class Occurrences(val count: Int) : EndCondition()

    /** Stop once [timestamp] has been passed. */
    data class EndTimestamp(val timestamp: Instant) : EndCondition()
}
