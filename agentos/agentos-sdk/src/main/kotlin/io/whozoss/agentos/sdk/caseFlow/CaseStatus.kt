package io.whozoss.agentos.sdk.caseFlow

/**
 * Status of a case during its lifecycle
 */
enum class CaseStatus {
    /**
     * Case has been created but not started
     */
    PENDING,

    /**
     * Case is currently being processed
     */
    RUNNING,

    /**
     * Case is being stopped
     */
    STOPPING,

    /**
     * Case encountered an error
     */
    ERROR,

    /**
     * Case was manually stopped
     */
    STOPPED,
}
