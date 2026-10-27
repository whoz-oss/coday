package io.whozoss.agentos.configAgent

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "A frozen configuration plan as the viewer may see it, with the live status of each operation.")
data class ConfigChangeViewDto(
    val questionId: UUID,
    val caseId: UUID,
    val recipientUserId: UUID,
    val agentName: String,
    val summary: String,
    val planHash: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val state: ConfigChangeState,
    @field:Schema(description = "True when the viewer is the recipient and the plan is open.")
    val canApply: Boolean,
    @field:Schema(description = "True when the viewer is the recipient and the plan is open or expired: rejecting an expired plan still answers the agent.")
    val canReject: Boolean = false,
    val operations: List<ConfigOperationViewDto>,
    val warnings: List<String>,
)
