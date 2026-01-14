package io.biznet.agentos.agents.api

import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.agents.domain.AgentContext
import io.biznet.agentos.agents.domain.AgentQueryResponse
import io.biznet.agentos.agents.domain.AgentStatus
import io.biznet.agentos.agents.domain.ContextType
import io.biznet.agentos.agents.service.AgentRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST API controller for agent management and querying
 */
@RestController
@RequestMapping("/api/agents")
class AgentController(
    private val agentRegistry: AgentRegistry,
) {
    /**
     * Get all registered agents
     */
    @GetMapping
    fun getAllAgents(): ResponseEntity<List<Agent>> = ResponseEntity.ok(agentRegistry.getAllAgents())

    /**
     * Get a specific agent by ID
     */
    @GetMapping("/{agentId}")
    fun getAgent(
        @PathVariable agentId: String,
    ): ResponseEntity<Agent> {
        val agent = agentRegistry.getAgent(agentId)
        return if (agent != null) {
            ResponseEntity.ok(agent)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Query agents based on context
     */
    @PostMapping("/query")
    fun queryAgents(
        @RequestBody request: AgentQueryRequest,
    ): ResponseEntity<AgentQueryResponse> {
        val context =
            AgentContext(
                contextTypes = request.contextTypes?.toSet() ?: emptySet(),
                tags = request.tags?.toSet() ?: emptySet(),
                capabilities = request.capabilities?.toSet() ?: emptySet(),
                excludeStatuses = request.excludeStatuses?.toSet() ?: emptySet(),
                minPriority = request.minPriority,
                maxResults = request.maxResults,
            )

        val response = agentRegistry.findAgents(context)
        return ResponseEntity.ok(response)
    }

    /**
     * Query agents by context type (simplified endpoint)
     */
    @GetMapping("/by-context")
    fun getAgentsByContext(
        @RequestParam(required = false) contextTypes: List<ContextType>?,
        @RequestParam(required = false) tags: List<String>?,
        @RequestParam(required = false) capabilities: List<String>?,
        @RequestParam(required = false) maxResults: Int?,
    ): ResponseEntity<AgentQueryResponse> {
        val context =
            AgentContext(
                contextTypes = contextTypes?.toSet() ?: emptySet(),
                tags = tags?.toSet() ?: emptySet(),
                capabilities = capabilities?.toSet() ?: emptySet(),
                maxResults = maxResults,
            )

        val response = agentRegistry.findAgents(context)
        return ResponseEntity.ok(response)
    }

    /**
     * Register a new agent
     */
    @PostMapping
    fun registerAgent(
        @RequestBody agent: Agent,
    ): ResponseEntity<Agent> {
        val registered = agentRegistry.registerAgent(agent)
        return ResponseEntity.status(HttpStatus.CREATED).body(registered)
    }

    /**
     * Update an existing agent
     */
    @PutMapping("/{agentId}")
    fun updateAgent(
        @PathVariable agentId: String,
        @RequestBody agent: Agent,
    ): ResponseEntity<Agent> {
        val updated = agentRegistry.updateAgent(agentId, agent)
        return if (updated != null) {
            ResponseEntity.ok(updated)
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Delete an agent
     */
    @DeleteMapping("/{agentId}")
    fun deleteAgent(
        @PathVariable agentId: String,
    ): ResponseEntity<Void> {
        val deleted = agentRegistry.unregisterAgent(agentId)
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Get available context types
     */
    @GetMapping("/context-types")
    fun getContextTypes(): ResponseEntity<List<ContextType>> = ResponseEntity.ok(ContextType.entries)

    /**
     * Get available agent statuses
     */
    @GetMapping("/statuses")
    fun getStatuses(): ResponseEntity<List<AgentStatus>> = ResponseEntity.ok(AgentStatus.entries)
}

/**
 * Request DTO for querying agents
 */
data class AgentQueryRequest(
    val contextTypes: List<ContextType>? = null,
    val tags: List<String>? = null,
    val capabilities: List<String>? = null,
    val excludeStatuses: List<AgentStatus>? = null,
    val minPriority: Int? = null,
    val maxResults: Int? = null,
)
