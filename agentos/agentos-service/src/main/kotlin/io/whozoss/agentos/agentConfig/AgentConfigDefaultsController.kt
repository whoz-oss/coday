package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDefaultsDto
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AgentConfigDefaultsController(private val properties: AgentConfigProperties) {
    @GetMapping("/api/agent-configs/defaults")
    @PreAuthorize("isAuthenticated()")
    fun getAgentConfigDefaults(): AgentConfigDefaultsDto =
        AgentConfigDefaultsDto(delegationTimeoutSeconds = properties.delegationTimeoutSeconds)
}
