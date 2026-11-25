package io.whozoss.agentos.chat

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Controls how much of an AI provider failure is shown to the users of a case.
 *
 * When a provider answers a request with an HTTP error (a 400 on an invalid tool name, a 401 on a bad key, a 5xx),
 * the reason is in the response body. [exposeResponseBody] decides whether that body is appended to the error event
 * of the case, so that the user can understand and report the failure without an administrator reading DEBUG logs.
 *
 * The body shown is trimmed, bounded and has the obvious secrets masked (bearer tokens, `sk-...` keys, JSON fields
 * named like `api_key` or `authorization`), but a provider body is still free text written by a third party. A
 * deployment that considers it sensitive switches this off: the event then carries the status, the endpoint and the
 * request summary only, and the body stays in the DEBUG log.
 *
 * Known limitation: this switch governs the streaming path, where AgentOS builds the message. On the blocking path
 * (non-streamed calls) Spring AI already puts the body in the exception message before AgentOS sees it, so turning
 * the property off does not remove it there.
 *
 * Bound from the `agentos.ai-provider-errors` prefix.
 *
 * Override with the environment variable (Spring Boot relaxed binding):
 * - `AGENTOS_AI_PROVIDER_ERRORS_EXPOSE_RESPONSE_BODY` (default: true)
 *
 * Example (`application.yml`):
 * ```yaml
 * agentos:
 *   ai-provider-errors:
 *     expose-response-body: false
 * ```
 */
@ConfigurationProperties(prefix = "agentos.ai-provider-errors")
data class AiProviderErrorProperties(
    /** Append the (masked, bounded) provider response body to the error event of the case. */
    val exposeResponseBody: Boolean = true,
)
