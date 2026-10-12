package io.whozoss.agentos.git

/** Body of a workspace preparation retry. */
data class WorkspaceRetryRequest(
    /** Confirms that a setup command which may have partly run can run again. */
    val acknowledgeSetupReplay: Boolean = false,
)
