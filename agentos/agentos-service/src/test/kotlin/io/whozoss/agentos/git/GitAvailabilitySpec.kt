package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistryService

class GitAvailabilitySpec :
    StringSpec({
        val registry = mockk<ToolRegistryService>()
        val availability = GitAvailability(registry)

        "Git is available once a GIT tool plugin is registered" {
            every { registry.findPlugin("GIT") } returns mockk<ToolPlugin>()

            availability.isAvailable() shouldBe true
        }

        "Git is unavailable without a GIT tool plugin" {
            every { registry.findPlugin("GIT") } returns null

            availability.isAvailable() shouldBe false
        }
    })
