package io.whozoss.agentos.casePlugin

/**
 * Why a launch was refused: a business rule, never a failure. The name is the stable `errorType` the
 * `FreeCaseLauncher` tool reports.
 */
enum class CaseLaunchRefusal {
    /** The request names another user, which [CaseLaunchRequest.allowOtherUsers] does not allow. */
    OTHER_USERS_NOT_ALLOWED,

    /** The target user does not exist. */
    TARGET_USER_NOT_FOUND,

    /** The target user cannot read the namespace. */
    TARGET_NO_ACCESS,

    /** The agent is unknown, disabled or not deployed to the target user: the three look the same. */
    AGENT_NOT_AVAILABLE,
}
