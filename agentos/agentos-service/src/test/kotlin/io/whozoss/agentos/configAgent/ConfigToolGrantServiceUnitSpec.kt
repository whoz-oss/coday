package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.tool.ToolResolverService
import java.time.Clock
import java.util.UUID

/** Who gets the config tools: only an agent whose `integrations` names `AGENTOS_CONFIG`. */
class ConfigToolGrantServiceUnitSpec :
    StringSpec({
        val toolResolverService = mockk<ToolResolverService> { every { isToolAllowed(any(), any(), any()) } returns true }
        val service =
            ConfigToolGrantService(
                configReadService = mockk(),
                configNamespaceDiagnosis = mockk(),
                configPlanner = mockk(),
                toolResolverService = toolResolverService,
                objectMapper = jacksonObjectMapper(),
                clock = Clock.systemUTC(),
            )

        fun granted(integrations: Map<String, List<String>?>?) =
            service.grantTools(integrations, agentName = "agent", namespaceId = UUID.randomUUID(), userId = UUID.randomUUID(), caseId = null).map { it.name }

        "an agent without an integrations map, which elsewhere means all integrations, gets no config tool" {
            granted(null).shouldBeEmpty()
        }

        "an agent gets them only by naming AGENTOS_CONFIG, and an empty list opts out" {
            granted(mapOf("JIRA" to null)).shouldBeEmpty()
            granted(mapOf(ConfigAgentIntegration.TYPE to emptyList())).shouldBeEmpty()
            granted(mapOf(ConfigAgentIntegration.TYPE to null)) shouldContainExactlyInAnyOrder
                listOf(ConfigAgentIntegration.READ_TOOL_NAME, ConfigAgentIntegration.PROPOSE_TOOL_NAME)
        }
    })
