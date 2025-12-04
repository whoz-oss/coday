package io.biznet.agentos.orchestration

interface IAgentService {
    fun findAgentByName(namePart: String): IAgent
}
