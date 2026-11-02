package io.whozoss.agentos.codayAgent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.configAgent.ConfigAgentIntegration
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [CodayAgentSeeder]: the namespace-scoped agent `Coday` declared by
 * [CodayAgentProperties.enabled], with the semantics of the other seeders (existence not content,
 * create-only, tolerant of a concurrent creation).
 */
class CodayAgentSeederSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()

        fun agent(
            namespace: UUID?,
            name: String = CodayAgentSeeder.AGENT_NAME,
            enabled: Boolean = true,
        ) = AgentConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespace,
            name = name,
            enabled = enabled,
        )

        class Fixture(
            enabled: Boolean = true,
        ) {
            val agents = mockk<AgentConfigService>()
            val seeder = CodayAgentSeeder(CodayAgentProperties(enabled), agents)

            /** The namespace owns nothing. */
            fun ownsNothing() {
                every { agents.findByNamespace(namespaceId, true) } returns emptyList()
            }
        }

        "creates an enabled agent owned by the namespace, on BIG, with the config, case-exchange and namespace-exchange integrations" {
            val f = Fixture()
            f.ownsNothing()
            val created = slot<AgentConfig>()
            every { f.agents.create(capture(created)) } answers { created.captured }

            val result = f.seeder.ensureIn(namespaceId)

            result shouldBe created.captured
            // Namespace-scoped, never a platform agent.
            created.captured.namespaceId shouldBe namespaceId
            (created.captured.namespaceId == null) shouldBe false
            created.captured.name shouldBe "Coday"
            created.captured.modelName shouldBe "BIG"
            created.captured.enabled shouldBe true
            // A null value is "enabled with all tools"; an empty list would be an opt-out.
            created.captured.integrations shouldBe
                mapOf(
                    ConfigAgentIntegration.TYPE to null,
                    ExchangeIntegrationTypes.CASE to null,
                    ExchangeIntegrationTypes.NAMESPACE to null,
                )
        }

        "the contract names stay stable" {
            CodayAgentSeeder.AGENT_NAME shouldBe "Coday"
            ConfigAgentIntegration.TYPE shouldBe "AGENTOS_CONFIG"
            ExchangeIntegrationTypes.CASE shouldBe "CASE_FILE_EXCHANGE"
            ExchangeIntegrationTypes.NAMESPACE shouldBe "NAMESPACE_FILE_EXCHANGE"
        }

        "ships instructions from the classpath that say the agent proposes and never applies" {
            val f = Fixture()
            f.ownsNothing()
            val created = slot<AgentConfig>()
            every { f.agents.create(capture(created)) } answers { created.captured }

            f.seeder.ensureIn(namespaceId)

            val instructions = created.captured.instructions!!
            instructions shouldContain "You are Coday"
            instructions shouldContain "AGENTOS_CONFIG__propose"
            instructions shouldContain "never apply anything yourself"
            instructions shouldContain "AI_PROVIDER"
            instructions shouldContain "personal scope"
            instructions shouldContain "namespace-exchange"
        }

        "declares existence, not content: an agent the namespace already owns is returned untouched" {
            val f = Fixture()
            val own = agent(namespaceId)
            every { f.agents.findByNamespace(namespaceId, true) } returns listOf(own)

            f.seeder.ensureIn(namespaceId) shouldBe own

            verify(exactly = 0) { f.agents.create(any()) }
            verify(exactly = 0) { f.agents.update(any()) }
            verify(exactly = 0) { f.agents.delete(any()) }
        }

        "an agent named like Coday that the user made is theirs: found case-insensitively, left alone" {
            val f = Fixture()
            val theirs = agent(namespaceId, name = "coday")
            every { f.agents.findByNamespace(namespaceId, true) } returns listOf(agent(namespaceId, name = "Dev"), theirs)

            f.seeder.ensureIn(namespaceId) shouldBe theirs

            verify(exactly = 0) { f.agents.create(any()) }
            verify(exactly = 0) { f.agents.update(any()) }
        }

        "a disabled agent of the namespace counts as existing and is not duplicated" {
            val f = Fixture()
            val disabled = agent(namespaceId, enabled = false)
            every { f.agents.findByNamespace(namespaceId, true) } returns listOf(disabled)

            f.seeder.ensureIn(namespaceId) shouldBe disabled

            verify(exactly = 0) { f.agents.create(any()) }
            verify(exactly = 0) { f.agents.update(any()) }
        }

        "a platform Coday does not prevent creating the namespace one" {
            val f = Fixture()
            // findByNamespace is scoped to the namespace: the platform agent is not in its result. findByName, whose
            // platform fallback would have said 'exists', must not be what decides.
            every { f.agents.findByNamespace(namespaceId, true) } returns emptyList()
            every { f.agents.findByName(any(), any()) } returns agent(null)
            val created = slot<AgentConfig>()
            every { f.agents.create(capture(created)) } answers { created.captured }

            f.seeder.ensureIn(namespaceId) shouldBe created.captured

            created.captured.namespaceId shouldBe namespaceId
            verify(exactly = 1) { f.agents.create(any()) }
            verify(exactly = 0) { f.agents.findByName(any(), any()) }
            verify(exactly = 0) { f.agents.findByNamespace(null, any()) }
        }

        "nothing is created and null is returned when disabled" {
            val f = Fixture(enabled = false)

            f.seeder.ensureIn(namespaceId).shouldBeNull()

            verify(exactly = 0) { f.agents.create(any()) }
            verify(exactly = 0) { f.agents.update(any()) }
            verify(exactly = 0) { f.agents.delete(any()) }
        }

        "create-only: disabled, an existing agent is neither deleted nor rewritten" {
            val f = Fixture(enabled = false)

            f.seeder.ensureIn(namespaceId).shouldBeNull()

            verify(exactly = 0) { f.agents.findByNamespace(any(), any()) }
            verify(exactly = 0) { f.agents.delete(any()) }
            verify(exactly = 0) { f.agents.update(any()) }
        }

        "a conflict from a concurrent creation is not a failure: the winner's agent is returned" {
            val f = Fixture()
            val winner = agent(namespaceId)
            every { f.agents.findByNamespace(namespaceId, true) } returnsMany listOf(emptyList(), listOf(winner))
            every { f.agents.create(any()) } throws ConflictException("already exists")

            f.seeder.ensureIn(namespaceId) shouldBe winner
        }

        "a duplicate-name refusal is the lost race when the namespace owns the agent now" {
            val f = Fixture()
            val winner = agent(namespaceId)
            every { f.agents.findByNamespace(namespaceId, true) } returnsMany listOf(emptyList(), listOf(winner))
            every { f.agents.create(any()) } throws IllegalArgumentException("An AgentConfig named 'Coday' already exists")

            f.seeder.ensureIn(namespaceId) shouldBe winner
        }

        "a refusal that is not a lost race still propagates" {
            val f = Fixture()
            f.ownsNothing()
            every { f.agents.create(any()) } throws IllegalArgumentException("bad")

            shouldThrow<IllegalArgumentException> { f.seeder.ensureIn(namespaceId) }
        }

        "a failure that is not a conflict propagates" {
            val f = Fixture()
            f.ownsNothing()
            every { f.agents.create(any()) } throws UnprocessableEntityException("bad")

            shouldThrow<UnprocessableEntityException> { f.seeder.ensureIn(namespaceId) }
        }
    })
