package io.whozoss.agentos.casePlugin

/**
 * Why a start was refused: a business rule, never a failure. The name is the stable `errorType` the
 * starter tools report.
 */
enum class CaseStarterRefusal {
    /**
     * The calling case was itself started by another case, or was delegated from a case that was: a started case
     * cannot start another one, so starts never chain.
     */
    START_DEPTH_EXCEEDED,

    /** Only an ADMIN of the namespace (federation) can start a case for another user. */
    FORBIDDEN,

    /** The target user does not exist. */
    TARGET_USER_NOT_FOUND,

    /** The target user cannot read the namespace. */
    TARGET_NO_ACCESS,

    /** The agent is unknown, disabled or not deployed to the target user: the three look the same. */
    AGENT_NOT_AVAILABLE,

    /** A case with the same dispatchKey already exists — this dispatch was already delivered. */
    ALREADY_DISPATCHED,

    /** The referenced Prompt does not exist, or has no usable content. */
    PROMPT_NOT_FOUND,
}
