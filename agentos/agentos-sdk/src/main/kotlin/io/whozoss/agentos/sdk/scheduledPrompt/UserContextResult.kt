package io.whozoss.agentos.sdk.scheduledPrompt

/**
 * Typed outcome of [UserContextProvider.provideUserContext].
 *
 * Distinguishes three situations that were previously all collapsed into a single `null` return:
 *
 * - [Success] — context resolved; [sessionContext] is forwarded to `CaseService.addMessage`.
 * - [PermanentFailure] — misconfiguration or a 4xx-class error; retrying would not help.
 *   The executor marks the [io.whozoss.agentos.scheduledPrompt.ScheduledPromptUserRun] as
 *   `FAILED` immediately.
 * - [TransientFailure] — timeout, 5xx, or network issue; the lease expiry mechanism will
 *   reclaim the UserRun on the next scheduler tick so it can be retried.
 */
sealed class UserContextResult {

    /**
     * Context resolved successfully.
     *
     * @param sessionContext Map to inject as `sessionContext` in `AddMessageRequest`.
     *   May be null when the plugin intentionally produces no context (non-fatal).
     */
    data class Success(val sessionContext: Map<String, Any?>?) : UserContextResult()

    /**
     * Permanent failure — retrying will not help (e.g. misconfiguration, 4xx response).
     *
     * The executor marks the UserRun as `FAILED` immediately.
     *
     * @param reason Human-readable description stored as the UserRun error message.
     */
    data class PermanentFailure(val reason: String) : UserContextResult()

    /**
     * Transient failure — retrying may succeed (e.g. timeout, 5xx, network error).
     *
     * The executor stops processing the UserRun without marking it terminal.
     * The lease expiry mechanism reclaims it on the next scheduler tick.
     *
     * @param reason Human-readable description for logging.
     */
    data class TransientFailure(val reason: String) : UserContextResult()
}
