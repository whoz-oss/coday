package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Writes one configuration change on behalf of the user of the current HTTP request.
 *
 * The port lets phase 2 swap the implementation, entity by entity, for the service-level permission
 * methods (`*ForCaller(callerId, ...)`, #1492) without touching the planner, the status or the card.
 */
interface ConfigWriter {
    /** Creates an entity from its DTO fields and returns its id. */
    fun create(
        kind: ConfigKind,
        dto: ObjectNode,
    ): UUID

    /** Re-reads the entity as the REST API returns it, lets [change] edit it, and writes it back. */
    fun update(
        kind: ConfigKind,
        id: UUID,
        change: (ObjectNode) -> Unit,
    )

    fun setEnabled(
        agentId: UUID,
        enabled: Boolean,
    )

    fun setDeployed(
        namespaceId: UUID,
        agentId: UUID,
        deployed: Boolean,
    )
}
