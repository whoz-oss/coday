package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode

class SchedulerLoopAgentSeederUnitSpec :
    StringSpec({
        val properties = SchedulerProperties()

        "creates the platform LOOP agent with the user-group workflow tools when absent" {
            val service = mockk<AgentConfigService>()
            val created = slot<AgentConfig>()
            every { service.findByName(null, "scheduler-loop") } returns null
            every { service.create(capture(created)) } answers { firstArg() }

            SchedulerLoopAgentSeeder(service, properties).seed()

            created.captured.namespaceId shouldBe null
            created.captured.executionMode shouldBe ExecutionMode.LOOP
            created.captured.enabled shouldBe true
            created.captured.integrations shouldBe mapOf("USER_GROUP" to listOf("listAgentUsers"), "CASE" to listOf("BatchCaseStarter"))
        }

        "leaves an existing agent untouched, and tolerates a concurrent creation" {
            val existing = mockk<AgentConfigService>()
            every { existing.findByName(null, "scheduler-loop") } returns SchedulerLoopAgentSeeder.loopAgent("scheduler-loop")
            SchedulerLoopAgentSeeder(existing, properties).seed()
            verify(exactly = 0) { existing.create(any()) }

            val racing = mockk<AgentConfigService>()
            every { racing.findByName(null, "scheduler-loop") } returns null
            every { racing.create(any()) } throws IllegalArgumentException("already exists")
            SchedulerLoopAgentSeeder(racing, properties).seed()
        }
    })
