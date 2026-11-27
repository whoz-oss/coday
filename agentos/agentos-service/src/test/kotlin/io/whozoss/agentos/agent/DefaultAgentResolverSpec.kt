package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class DefaultAgentResolverSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        fun resolver(
            namespaceDefault: String?,
            platformDefault: String?,
            agentService: AgentService,
        ): DefaultAgentResolver {
            val namespaceService =
                mockk<NamespaceService> {
                    every { findById(namespaceId) } returns
                        Namespace(
                            metadata = EntityMetadata(id = namespaceId),
                            name = "ns",
                            defaultAgentName = namespaceDefault,
                        )
                }
            return DefaultAgentResolver(namespaceService, agentService, AgentConfigProperties(agentName = platformDefault))
        }

        "the namespace default wins over the platform one, resolved for the user" {
            val agents = mockk<AgentService> { every { resolveAgentName("Arch", namespaceId, userId) } returns "Archay" }

            resolver("Arch", "Coday", agents).resolve(namespaceId, userId) shouldBe
                DefaultAgentResolution(configuredName = "Arch", fromNamespace = true, agentName = "Archay")
            verify(exactly = 1) { agents.resolveAgentName("Arch", namespaceId, userId) }
        }

        "the platform default applies when the namespace names none" {
            val agents = mockk<AgentService> { every { resolveAgentName("Coday", namespaceId, userId) } returns "Coday" }

            resolver(null, "Coday", agents).resolve(namespaceId, userId) shouldBe
                DefaultAgentResolution(configuredName = "Coday", fromNamespace = false, agentName = "Coday")
        }

        "no agent answers when the configured name resolves to none for the user" {
            val agents = mockk<AgentService> { every { resolveAgentName(any(), any(), any()) } returns null }

            resolver("Archay", null, agents).resolve(namespaceId, userId).agentName shouldBe null
        }

        "nothing is resolved when neither the namespace nor the platform names a default" {
            val agents = mockk<AgentService>()

            resolver(null, null, agents).resolve(namespaceId, userId) shouldBe
                DefaultAgentResolution(configuredName = null, fromNamespace = false, agentName = null)
            verify(exactly = 0) { agents.resolveAgentName(any(), any(), any()) }
        }
    })
