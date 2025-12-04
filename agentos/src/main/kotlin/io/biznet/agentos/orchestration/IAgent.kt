package io.biznet.agentos.orchestration

import java.util.UUID

interface IAgent {
    val id: UUID
    val name: String

    fun run(events: List<CaseEvent>)
}
