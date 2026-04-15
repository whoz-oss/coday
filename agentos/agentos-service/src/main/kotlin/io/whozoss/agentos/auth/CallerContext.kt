package io.whozoss.agentos.auth

/**
 * Tracks the last authenticated message sender in a Case.
 * Updated exclusively by the authenticated layer (controllers/SecurityService).
 * Agents and plugins MUST NOT modify this context.
 *
 * Uses @Volatile for thread-safety in coroutine context (Decision D9, NFR12).
 * No ThreadLocal, no CoroutineContext.Key.
 */
class CallerContext {
    @Volatile
    private var lastSenderId: String? = null

    @Volatile
    private var lastSenderName: String? = null

    fun setLastMessageSender(userId: String, displayName: String) {
        lastSenderId = userId
        lastSenderName = displayName
    }

    fun getCallerId(): String? = lastSenderId

    fun getCallerDisplayName(): String? = lastSenderName
}
