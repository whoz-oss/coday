package io.whozoss.agentos.sdk.api.usageRecord

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * HTTP representation of a [io.whozoss.agentos.usage.UsageRecord].
 *
 * [cost] is `null` when the pricing for the model was not configured at the time of the
 * call — null means "unknown", not zero. Callers must not treat null as 0.0.
 *
 * All costs share a single implicit currency unit (USD).
 */
@Schema(name = "UsageRecord")
data class UsageRecordDto(
    val id: UUID,
    val namespaceId: UUID,
    val caseId: UUID,
    val userId: UUID? = null,
    val source: String,
    val outcome: String,
    val agentConfigId: UUID? = null,
    val agentName: String,
    val providerName: String? = null,
    val apiModelName: String? = null,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    /** null = cost unknown (pricing not configured), not zero. */
    @Schema(nullable = true)
    @field:Schema(types = ["number", "null"], format = "double")
    val cost: Double? = null,
    val timestamp: Instant,
    val createdOn: Instant,
)
