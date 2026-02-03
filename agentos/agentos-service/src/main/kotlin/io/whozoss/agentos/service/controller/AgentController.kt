package io.whozoss.agentos.service.controller

import io.whozoss.agentos.agent.AgentService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
}
