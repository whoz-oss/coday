package io.biznet.agentos.service.controller

import io.biznet.agentos.sdk.AgentInput
import io.biznet.agentos.sdk.AgentOutput
import io.biznet.agentos.service.service.AgentService
import org.springframework.web.bind.annotation.*

/**
 * REST controller for agent operations.
 */
@RestController
@RequestMapping("/api/agents")
class AgentController(
    private val agentService: AgentService,
) {
    @GetMapping
    fun listAgents() = agentService.listAgents()

    @PostMapping("/{agentName}/execute")
    suspend fun executeAgent(
        @PathVariable agentName: String,
        @RequestBody input: AgentInput,
    ): AgentOutput = agentService.executeAgent(agentName, input)
}
