package io.whozoss.agentos.service.agents.domain

import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.agent.AgentStatus
import io.whozoss.agentos.sdk.agent.ContextType

// Re-export the API from plugin-api module
// This maintains backward compatibility for existing code in AgentOS
typealias Agent = Agent
typealias AgentStatus = AgentStatus
typealias ContextType = ContextType
