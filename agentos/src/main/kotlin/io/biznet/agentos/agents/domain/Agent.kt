package io.biznet.agentos.agents.domain

import io.biznet.agentos.api.agent.Agent
import io.biznet.agentos.api.agent.AgentStatus
import io.biznet.agentos.api.agent.ContextType

// Re-export the API from plugin-api module
// This maintains backward compatibility for existing code in AgentOS
typealias Agent = Agent
typealias AgentStatus = AgentStatus
typealias ContextType = ContextType
