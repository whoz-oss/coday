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
 * @param namespaceId The namespace in which the current case is running.
 * @param userId The internal AgentOS UUID of the requesting user, or null when the
 *   user could not be resolved (e.g. unauthenticated or service-to-service call).
 * @param userExternalId The identity-provider key of the requesting user (e.g. email
 *   from Cloudflare JWT). Plugins that manage their own auth use this to resolve
 *   the user in their own system without going through AgentOS user management.
 * @param caseEvents A live snapshot of the current case's event list at the moment
 *   of tool invocation. Evaluated lazily — each call reflects events added during
 *   the current agent run, including prior tool responses in the same turn.
 * @param agentName The name of the agent that owns this tool invocation, or null when
 *   the context is not associated with a specific agent (e.g. tool-set resolution at
 *   namespace level). Plugins may use this to exclude the running agent from lists they
 *   build (e.g. the redirect tool excludes the calling agent from eligible targets).
 * @param credentialProvider Pre-scoped credential supplier for the integration this tool
 *   belongs to. Returns the user's stored credential (OAuth tokens, API keys, etc.) or
 *   null if not yet authenticated. Null when no AuthSetting is bound to the integration.
 */
data class ToolContext(
    val namespaceId: UUID,
    val userId: UUID?,
    val userExternalId: String?,
    val caseEvents: List<CaseEvent>,
    val agentName: String? = null,
    val credentialProvider: CredentialProvider? = null,
)
