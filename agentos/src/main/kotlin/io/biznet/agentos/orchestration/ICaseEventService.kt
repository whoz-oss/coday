package io.biznet.agentos.orchestration

interface ICaseEventService {
    fun save(event: CaseEvent): CaseEvent
}
