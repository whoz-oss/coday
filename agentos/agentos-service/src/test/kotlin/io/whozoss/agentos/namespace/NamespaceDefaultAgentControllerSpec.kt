package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.DefaultAgentResolution
import io.whozoss.agentos.agent.DefaultAgentResolver
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/** Authorization (`Namespace READ`) is declarative and covered by PreAuthorizeLabelConsistencySpec. */
class NamespaceDefaultAgentControllerSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()
        val user = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "alice@example.com")
        val userService = mockk<UserService> { every { getCurrentUser() } returns user }

        "answers the agent resolved for the caller" {
            val resolver =
                mockk<DefaultAgentResolver> {
                    every { resolve(namespaceId, user.id) } returns DefaultAgentResolution("Arch", true, "Archay")
                }

            NamespaceDefaultAgentController(resolver, userService).get(namespaceId).agentName shouldBe "Archay"
        }

        "answers no agent when none would answer" {
            val resolver =
                mockk<DefaultAgentResolver> {
                    every { resolve(namespaceId, user.id) } returns DefaultAgentResolution("Gone", true, null)
                }

            NamespaceDefaultAgentController(resolver, userService).get(namespaceId).agentName shouldBe null
        }
    })
