package io.whozoss.agentos.casePlugin

import java.util.UUID

/**
 * What a caller (today the `FreeCaseStarter` and `FreeCaseStarterForUser` tools) asks for: start [agentName] on [prompt].
 *
 * The `caller*` fields describe the running case and come from it, never from LLM input.
 *
 * @param callerCaseId The case that starts the new one: it becomes the origin
 *   ([io.whozoss.agentos.caseFlow.Case.originCaseId]) of the new one.
 * @param targetUserId The user the new case runs for. Null, or the caller itself: the caller. Any
 *   other user requires the caller to be ADMIN of the namespace; the target must exist and be able to
 *   read the namespace.
 * @param title Name of the new conversation. Null or blank: derived from the start of [prompt].
 * @param sessionContext What the caller hands to the new case: free key/value data, passed on as given to the
 *   first message, under the context the system resolves for the target user (see [CaseStarterService]). The
 *   calling case's own session context is never inherited.
 * @param translateFrom BCP-47 source language of [prompt] and [title]. When non-null and the target user has
 *   a different [io.whozoss.agentos.user.User.preferredLanguage], both are translated before the case is
 *   created. Null (the default) disables translation entirely — existing LLM callers are unaffected.
 *   This field is NOT exposed in the JSON schema of any LLM-facing tool.
 * @param scheduledPromptId Id of the [io.whozoss.agentos.scheduledPrompt.ScheduledPrompt] that triggered
 *   this start, written to [io.whozoss.agentos.caseFlow.Case.scheduledPromptId]. Null for non-scheduled starts.
 *   This field is NOT exposed in the JSON schema of any LLM-facing tool.
 * @param dispatchKey Optional idempotency key; when a non-deleted case already carries it the start is
 *   refused as a duplicate and nothing is written. NOT exposed in any LLM-facing tool schema.
 */
data class CaseStarterRequest(
    val callerUserId: UUID,
    val callerNamespaceId: UUID,
    val callerCaseId: UUID,
    val agentName: String,
    val prompt: String,
    val targetUserId: UUID? = null,
    val title: String? = null,
    val sessionContext: Map<String, Any?>? = null,
    val translateFrom: String? = null,
    val scheduledPromptId: UUID? = null,
    val dispatchKey: String? = null,
)
