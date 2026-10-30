package io.whozoss.agentos.configAgent

import java.util.UUID

data class PlannedTarget(
    val id: UUID,
    val name: String,
    val namespaceId: UUID?,
    val userId: UUID?,
)
