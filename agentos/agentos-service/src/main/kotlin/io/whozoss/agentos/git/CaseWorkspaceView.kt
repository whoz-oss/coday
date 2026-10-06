package io.whozoss.agentos.git

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** Preparation state shared by a root case and its descendants. */
@Schema(name = "CaseWorkspaceView")
data class CaseWorkspaceView(
    @Schema(description = "Whether the case's family owns a Git workspace")
    val equipped: Boolean,
    @Schema(description = "Root case that owns the family's workspace. Null when the family is not equipped")
    val rootCaseId: UUID? = null,
    @Schema(description = "Lifecycle of the workspace. Null when the family is not equipped")
    val status: CaseResourceStatus? = null,
    @Schema(description = "Why the last preparation failed. Never a secret")
    val failureReason: String? = null,
    @Schema(description = "Why the workspace of a deleted family is still kept. Never a secret")
    val cleanupReason: String? = null,
)
