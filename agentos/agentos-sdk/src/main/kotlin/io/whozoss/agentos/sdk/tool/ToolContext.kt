package io.whozoss.agentos.sdk.tool

import io.whozoss.agentos.sdk.auth.CredentialProvider
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.util.UUID

/**
 * Execution context passed to every [StandardTool.execute] call.
 *
 * Carries the identifiers and live event history that a tool may need to:
 * - scope API calls to the correct namespace and user
 * - verify that a read operation was performed before a mutation (anti-hallucination guard)
 * - resolve credentials for authenticated integrations
 *
 * [emitEvent] is available only during a live agent run. It lets a tool publish an
 * additional durable case event through the normal agent event flow; preview contexts
 * leave it null.
 */
data class ToolContext(
    val namespaceId: UUID,
    val userId: UUID?,
    val userExternalId: String?,
    val caseEvents: List<CaseEvent>,
    val agentName: String? = null,
    val credentialProvider: CredentialProvider? = null,
    /** Identifier of the ToolRequestEvent that caused this invocation, when available. */
    val toolRequestId: String? = null,
    /** Emits an additional case event into the current agent flow. Null in preview contexts. */
    val emitEvent: ((CaseEvent) -> Unit)? = null,
)
