package io.whozoss.agentos.codayAgent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
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
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.PersonalNamespaceProvisioner
import java.util.UUID

/**
 * Unit tests for [CodayAgentSeeder]: the reconciliation of the platform agent `Coday` declared by
 * [CodayAgentProperties.enabled], with the semantics of the other seeders (existence not content,
 * create-only, tolerant of a concurrent startup).
 */
class CodayAgentSeederSpec : StringSpec({
    fun existing() = AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = null, name = CodayAgentSeeder.AGENT_NAME)

    class Fixture(
        enabled: Boolean = true,
    ) {
        val agents = mockk<AgentConfigService>()
        val provisioner = mockk<PersonalNamespaceProvisioner>(relaxed = true)
        val seeder = CodayAgentSeeder(CodayAgentProperties(enabled), agents, provisioner)
    }

    "seeds an enabled platform agent on BIG with the config, case-exchange and namespace-exchange integrations when absent" {
        val f = Fixture()
        every { f.agents.findByName(null, "Coday") } returns null
        val created = slot<AgentConfig>()
        every { f.agents.create(capture(created)) } answers { created.captured }

        f.seeder.seed()

        created.captured.namespaceId shouldBe null
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
        every { f.agents.findByName(null, "Coday") } returns null
        val created = slot<AgentConfig>()
        every { f.agents.create(capture(created)) } answers { created.captured }

        f.seeder.seed()

        val instructions = created.captured.instructions!!
        instructions shouldContain "AGENTOS_CONFIG__propose"
        instructions shouldContain "never apply anything yourself"
        instructions shouldContain "AI_PROVIDER"
        instructions shouldContain "personal scope"
        instructions shouldContain "namespace-exchange"
    }

    "deploys the new agent on the personal namespaces of the users who already exist" {
        val f = Fixture()
        every { f.agents.findByName(null, "Coday") } returns null
        every { f.agents.create(any()) } answers { firstArg() }

        f.seeder.seed()

        verify(exactly = 1) { f.provisioner.ensureForAllUsers() }
    }

    "declares existence, not content: an existing agent is never rewritten" {
        val f = Fixture()
        every { f.agents.findByName(null, "Coday") } returns existing()

        f.seeder.seed()

        verify(exactly = 0) { f.agents.create(any()) }
        verify(exactly = 0) { f.agents.update(any()) }
        verify(exactly = 0) { f.provisioner.ensureForAllUsers() }
    }

    "create-only: disabling afterwards never deletes an already-seeded agent" {
        val f = Fixture(enabled = false)
        every { f.agents.findByName(null, "Coday") } returns existing()

        f.seeder.seed()

        verify(exactly = 0) { f.agents.delete(any()) }
        verify(exactly = 0) { f.agents.update(any()) }
        verify(exactly = 0) { f.agents.create(any()) }
    }

    "seeds nothing when disabled" {
        val f = Fixture(enabled = false)
        every { f.agents.findByName(null, "Coday") } returns null

        f.seeder.seed()

        verify(exactly = 0) { f.agents.create(any()) }
    }

    "a conflict from a concurrent startup is not a startup failure" {
        val f = Fixture()
        every { f.agents.findByName(null, "Coday") } returns null
        every { f.agents.create(any()) } throws ConflictException("already exists")

        f.seeder.seed()
    }

    "a duplicate-name refusal is the lost race when the agent is there now" {
        val f = Fixture()
        every { f.agents.findByName(null, "Coday") } returnsMany listOf(null, existing())
        every { f.agents.create(any()) } throws IllegalArgumentException("An AgentConfig named 'Coday' already exists")

        f.seeder.seed()
    }

    "a refusal that is not a lost race still propagates" {
        val f = Fixture()
        every { f.agents.findByName(null, "Coday") } returns null
        every { f.agents.create(any()) } throws IllegalArgumentException("bad")

        shouldThrow<IllegalArgumentException> { f.seeder.seed() }
    }

    "a failure that is not a conflict propagates" {
        val f = Fixture()
        every { f.agents.findByName(null, "Coday") } returns null
        every { f.agents.create(any()) } throws UnprocessableEntityException("bad")

        shouldThrow<UnprocessableEntityException> { f.seeder.seed() }
    }
})
