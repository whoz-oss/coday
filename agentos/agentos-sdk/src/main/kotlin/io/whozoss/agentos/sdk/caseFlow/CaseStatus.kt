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
    STOPPED;

    /** Returns true if the case has reached a final state and will no longer produce events. */
    fun isTerminal(): Boolean = this == STOPPED || this == ERROR
}
