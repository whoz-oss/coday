package io.whozoss.agentos.configAgent

import java.util.UUID

/**
 * Where an operation writes, or deploys for `DEPLOY` and `UNDEPLOY`.
 *
 * [namespaceRef] is set instead of [namespaceId] when the namespace is created by an operation of
 * the same plan: its id only exists once that operation has run.
 */
data class PlannedScope(
    val namespaceId: UUID?,
    val userId: UUID?,
    val namespaceRef: String? = null,
) {
    /** The namespace id, resolved from [createdIds] for a [namespaceRef]; null for platform or until that namespace exists. */
    fun namespaceIn(createdIds: Map<String, UUID>): UUID? = if (namespaceRef == null) namespaceId else createdIds[namespaceRef]
}
