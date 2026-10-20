package io.whozoss.agentos.casePlugin

import java.util.UUID

/**
 * What a caller (today the `FreeCaseLauncher` and `FreeCaseLauncherForUser` tools) asks for: start [agentName] on [prompt].
 *
 * The `caller*` fields describe the running case and come from it, never from LLM input.
 *
 * @param callerCaseId The case that launches: it becomes the origin
 *   ([io.whozoss.agentos.caseFlow.Case.originCaseId]) of the new one.
 * @param targetUserId The user the new case runs for. Null, or the caller itself: the caller. Any
 *   other user requires the caller to be ADMIN of the namespace; the target must exist and be able to
 *   read the namespace.
 * @param title Name of the new conversation. Null or blank: derived from the start of [prompt].
 * @param sessionContext What the caller hands to the new case: free key/value data, passed on as given to the
 *   first message, under the context the system resolves for the target user (see [CaseLaunchService]). The
 *   calling case's own session context is never inherited.
 */
data class CaseLaunchRequest(
    val callerUserId: UUID,
    val callerNamespaceId: UUID,
    val callerCaseId: UUID,
    val agentName: String,
    val prompt: String,
    val targetUserId: UUID? = null,
    val title: String? = null,
    val sessionContext: Map<String, Any?>? = null,
)
