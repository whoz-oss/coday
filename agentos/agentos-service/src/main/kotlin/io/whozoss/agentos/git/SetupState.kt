package io.whozoss.agentos.git

/** How far the setup command of a family's workspace went. */
enum class SetupState {
    /** Never launched: a preparation may run it. */
    NOT_STARTED,

    /** Launched without a recorded end: running, or interrupted with unknown effects. */
    STARTED,

    /** Ran to the end. Never run again for this workspace. */
    COMPLETED,
}
