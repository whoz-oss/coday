package io.whozoss.agentos.namespace

import java.util.UUID

/** Published by [NamespaceService.delete] once the namespace [namespaceId] is deleted: what serves it goes too. */
data class NamespaceDeletedEvent(
    val namespaceId: UUID,
)
