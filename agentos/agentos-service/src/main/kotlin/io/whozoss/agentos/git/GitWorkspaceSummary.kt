package io.whozoss.agentos.git

import java.util.UUID

/** Preparation state shared by a root case and its descendants. */
data class CaseWorkspaceView(
    val equipped: Boolean,
    val rootCaseId: UUID? = null,
    val status: String? = null,
    val failureReason: String? = null,
    val cleanupReason: String? = null,
)
