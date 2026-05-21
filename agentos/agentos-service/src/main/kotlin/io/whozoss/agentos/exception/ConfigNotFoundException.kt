package io.whozoss.agentos.exception

import java.util.UUID

/**
 * Thrown by [io.whozoss.agentos.reconciliation.ConfigMergeService.resolve] when none of the three
 * precedence layers contains a configuration for the requested triple.
 *
 * Carries the failing triple in both the structured fields (for typed handling upstream) and
 * the message (for log readability). The fail-closed posture (NFR-REL-1) requires this typed
 * exception over a `null` return — callers MUST observe an explicit signal.
 */
class ConfigNotFoundException(
    val namespaceId: UUID,
    val userId: UUID,
    val name: String,
) : RuntimeException(
        "No config found for triple (namespaceId=$namespaceId, userId=$userId, name=$name)",
    )
