package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** Lifecycle of a proposal, derived from the case events and the clock; never stored. */
enum class ConfigChangeState {
    OPEN,
    IN_PROGRESS,
    ANSWERED,
    EXPIRED,
    SUPERSEDED,
}

/** Where one operation stands against the real state, recomputed at every read. */
enum class ConfigOperationStatus {
    PENDING,
    APPLIED,
    ALREADY_APPLIED,
    CONFLICT,
    NOT_VISIBLE,
}

enum class ConfigResultStatus {
    APPLIED,
    ALREADY_APPLIED,
    DENIED,
    NOT_FOUND,
    CONFLICT,
    INVALID,
    FAILED,
    NOT_RUN,
}

enum class ConfigChangeDecision {
    APPLIED,
    PARTIAL,
    FAILED,
    REJECTED,
}

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
    val operations: List<ConfigOperationViewDto>,
    val warnings: List<String>,
)

data class ConfigOperationViewDto(
    val ref: String,
    val order: Int,
    val kind: ConfigKind,
    val action: ConfigAction,
    @field:Schema(description = "Name of the entity created or targeted.")
    val name: String,
    val targetId: UUID?,
    @field:Schema(description = "Scope of the entity: null namespaceId and userId mean platform.")
    val namespaceId: UUID?,
    val userId: UUID?,
    val affects: ConfigAffects,
    val deployTo: ConfigDeployTarget?,
    @field:Schema(description = "CREATE only: the fields of the new entity, secrets excluded.")
    val value: JsonNode?,
    @field:Schema(description = "UPDATE only: each field with its current value (masked) and the proposed one.")
    val changes: List<ConfigFieldChangeDto>,
    val secretInputs: List<ConfigSecretInputDto>,
    val status: ConfigOperationStatus,
)

data class ConfigFieldChangeDto(
    val path: String,
    @field:Schema(description = "Current value, masked; null when absent or not visible to the viewer.")
    val before: JsonNode?,
    val after: JsonNode?,
    val remove: Boolean,
)

data class ConfigSecretInputDto(
    @field:Schema(description = "Key to send in ConfigChangeApplyRequest.secrets: '<ref>.<path>'.")
    val key: String,
    val path: String,
    val label: String,
)

data class ConfigChangeApplyRequest(
    @field:Schema(description = "planHash of the view the user approved; the server refuses to apply anything else.")
    val expectedPlanHash: String,
    @field:Schema(description = "Secret values typed by the user, keyed '<ref>.<path>'.")
    val secrets: Map<String, String> = emptyMap(),
)

data class ConfigChangeRejectRequest(
    val comment: String? = null,
)

data class ConfigChangeReportDto(
    val decision: ConfigChangeDecision,
    val appliedBy: UUID,
    val results: List<ConfigOperationResultDto>,
)

data class ConfigOperationResultDto(
    val ref: String,
    val status: ConfigResultStatus,
    val entityId: UUID? = null,
    val message: String? = null,
)
