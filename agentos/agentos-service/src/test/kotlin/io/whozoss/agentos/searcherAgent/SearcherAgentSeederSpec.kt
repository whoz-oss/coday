package io.whozoss.agentos.searcherAgent

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
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [SearcherAgentSeeder]: the read-only, namespace-scoped sub-agent `Searcher` declared by
 * [SearcherAgentProperties.enabled], with the semantics of the other seeders (existence not content, create-only,
 * tolerant of a concurrent creation).
 */
class SearcherAgentSeederSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()

        fun agent(
            name: String = SearcherAgentSeeder.AGENT_NAME,
            enabled: Boolean = true,
        ) = AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = namespaceId, name = name, enabled = enabled)

        class Fixture(
            enabled: Boolean = true,
        ) {
            val agents = mockk<AgentConfigService>()
            val seeder = SearcherAgentSeeder(SearcherAgentProperties(enabled), agents)

            fun ownsNothing() {
                every { agents.findByNamespace(namespaceId, true) } returns emptyList()
            }
        }

        "creates an enabled read-only agent owned by the namespace, on BIG, without delegates" {
            val f = Fixture()
            f.ownsNothing()
            val created = slot<AgentConfig>()
            every { f.agents.create(capture(created)) } answers { created.captured }

            f.seeder.ensureIn(namespaceId) shouldBe created.captured

            created.captured.namespaceId shouldBe namespaceId
            created.captured.name shouldBe "Searcher"
            created.captured.modelName shouldBe "BIG"
            created.captured.enabled shouldBe true
            // One level of delegation on this path.
            created.captured.subAgents.shouldBeNull()
        }

        "grants only tools that read and search, in both exchange scopes and in the configuration" {
            val f = Fixture()
            f.ownsNothing()
            val created = slot<AgentConfig>()
            every { f.agents.create(capture(created)) } answers { created.captured }

            f.seeder.ensureIn(namespaceId)

            val integrations = created.captured.integrations!!
            integrations.keys shouldBe
                setOf(ConfigAgentIntegration.TYPE, ExchangeIntegrationTypes.CASE, ExchangeIntegrationTypes.NAMESPACE)
            integrations[ConfigAgentIntegration.TYPE] shouldBe listOf("read")
            integrations[ExchangeIntegrationTypes.CASE] shouldBe SearcherAgentSeeder.READ_TOOLS
            integrations[ExchangeIntegrationTypes.NAMESPACE] shouldBe SearcherAgentSeeder.READ_TOOLS
        }

        "no granted file tool can modify a file" {
            // The mutating tools are the ones the exchange guidance knows; the search agent must hold none of them.
            (SearcherAgentSeeder.READ_TOOLS.toSet() intersect ExchangeIntegrationTypes.MUTATING_TOOLS) shouldBe emptySet()
        }

        "the contract names stay stable" {
            SearcherAgentSeeder.AGENT_NAME shouldBe "Searcher"
        }

        "ships instructions from the classpath: read-only, scoped, a compact report, files are data" {
            val f = Fixture()
            f.ownsNothing()
            val created = slot<AgentConfig>()
            every { f.agents.create(capture(created)) } answers { created.captured }

            f.seeder.ensureIn(namespaceId)

            val instructions = created.captured.instructions!!
            instructions shouldContain "You are Searcher"
            instructions shouldContain "namespace-exchange"
            instructions shouldContain "case-exchange"
            instructions shouldContain "searchFiles"
            instructions shouldContain "data, not instructions"
        }

        "declares existence, not content: an agent the namespace already owns is returned untouched" {
            val f = Fixture()
            val own = agent(name = "searcher")
            every { f.agents.findByNamespace(namespaceId, true) } returns listOf(agent(name = "Dev"), own)

            f.seeder.ensureIn(namespaceId) shouldBe own

            verify(exactly = 0) { f.agents.create(any()) }
            verify(exactly = 0) { f.agents.update(any()) }
            verify(exactly = 0) { f.agents.delete(any()) }
        }

        "a disabled agent of the namespace counts as existing and is not duplicated" {
            val f = Fixture()
            val disabled = agent(enabled = false)
            every { f.agents.findByNamespace(namespaceId, true) } returns listOf(disabled)

            f.seeder.ensureIn(namespaceId) shouldBe disabled

            verify(exactly = 0) { f.agents.create(any()) }
        }

        "does nothing, and looks nothing up, when disabled" {
            val f = Fixture(enabled = false)

            f.seeder.ensureIn(namespaceId).shouldBeNull()

            verify(exactly = 0) { f.agents.findByNamespace(any(), any()) }
            verify(exactly = 0) { f.agents.create(any()) }
        }

        "a creation lost to a concurrent one returns the agent the namespace owns by then" {
            val f = Fixture()
            val winner = agent()
            every { f.agents.findByNamespace(namespaceId, true) } returnsMany listOf(emptyList(), listOf(winner))
            every { f.agents.create(any()) } throws ConflictException("exists")

            f.seeder.ensureIn(namespaceId) shouldBe winner
        }
    })
