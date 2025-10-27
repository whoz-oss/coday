package io.biznet.agentos.tools.service

import io.biznet.agentos.tools.domain.InternalTool
import io.biznet.agentos.tools.domain.MCPTool
import io.biznet.agentos.tools.domain.StandardTool
import org.springframework.stereotype.Service

@Service
class ToolRegistry {
    fun findTools(): List<StandardTool> = listOf(
        MCPTool(
            "github", version = "1.0",
            description = "github operations",
            inputSchema = "no",
        ),
        InternalTool(
            "delegateToAgent", version = "2.0",
            description = "delegate to agent",
            inputSchema = "no"
        ),
        InternalTool(
            "askToUser", version = "2.0",
            description = "ask information to user",
            inputSchema = "no"
        ),
        )
}