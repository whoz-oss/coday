package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.namespace.NamespaceRepository.Companion.NAMESPACE_PARENT_KEY
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException

/**
 * Shared namespace persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (Testcontainers or embedded)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 */
abstract class AbstractNamespacePersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: NamespaceRepository

    @Autowired
    lateinit var agentConfigRepo: AgentConfigRepository

    @Autowired
    lateinit var driver: Driver

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same namespace" {
            val ns = Namespace(metadata = EntityMetadata(), name = "engineering", description = "Engineering")
            val saved = repo.save(ns)
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().name shouldBe "engineering"
        }

        "findByParent returns all non-removed namespaces" {
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-1"))
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-2"))
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-3"))
            repo.findByParent(NAMESPACE_PARENT_KEY) shouldHaveSize 3
        }

        "soft delete removes namespace from findByIds" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "to-delete"))
            repo.delete(ns.id).shouldBeTrue()
            repo.findByIds(listOf(ns.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "double"))
            repo.delete(ns.id).shouldBeTrue()
            repo.delete(ns.id).shouldBeFalse()
        }

        "deleteByParent removes all namespaces" {
            repo.save(Namespace(metadata = EntityMetadata(), name = "a"))
            repo.save(Namespace(metadata = EntityMetadata(), name = "b"))
            val deleted = repo.deleteByParent(NAMESPACE_PARENT_KEY)
            deleted shouldBe 2
            repo.findByParent(NAMESPACE_PARENT_KEY).shouldBeEmpty()
        }

        "update: saving with same id replaces the node" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "original"))
            repo.save(ns.copy(name = "updated"))
            val found = repo.findByIds(listOf(ns.id))
            found shouldHaveSize 1
            found.first().name shouldBe "updated"
        }

        "configPath is persisted and retrieved" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "coday-project", configPath = "/opt/coday"))
            val found = repo.findByIds(listOf(ns.id)).first()
            found.configPath shouldBe "/opt/coday"
        }

        "configPath can be cleared by updating to null" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "project", configPath = "/opt/coday"))
            repo.save(ns.copy(configPath = null))
            val found = repo.findByIds(listOf(ns.id)).first()
            found.configPath shouldBe null
        }

        "configPath can be set on a namespace that had none" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "project", configPath = null))
            repo.save(ns.copy(configPath = "/opt/coday"))
            val found = repo.findByIds(listOf(ns.id)).first()
            found.configPath shouldBe "/opt/coday"
        }

        "save throws on duplicate externalId" {
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-a", externalId = "ext-1"))

            shouldThrow<DataIntegrityViolationException> {
                repo.save(Namespace(metadata = EntityMetadata(), name = "ns-b", externalId = "ext-1"))
            }
        }

        "multiple namespaces with null externalId are allowed" {
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-a", externalId = null))
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-b", externalId = null))
        }

        // -------------------------------------------------------------------------
        // deployAgents / undeployAgents
        // -------------------------------------------------------------------------

        "deployAgents creates a DEPLOYED_TO relationship" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "ns"))
            val agent = agentConfigRepo.save(AgentConfig(metadata = EntityMetadata(), namespaceId = ns.id, name = "agent"))

            repo.deployAgents(ns.id, listOf(agent.id))

            val count = deployedToCount(ns.id.toString(), agent.id.toString())
            count shouldBe 1
        }

        "deployAgents is idempotent: double call creates only one relationship" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "ns"))
            val agent = agentConfigRepo.save(AgentConfig(metadata = EntityMetadata(), namespaceId = ns.id, name = "agent"))

            repo.deployAgents(ns.id, listOf(agent.id))
            repo.deployAgents(ns.id, listOf(agent.id))

            val count = deployedToCount(ns.id.toString(), agent.id.toString())
            count shouldBe 1
        }

        "deployAgents creates relationships for all agents in the list" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "ns"))
            val agent1 = agentConfigRepo.save(AgentConfig(metadata = EntityMetadata(), namespaceId = ns.id, name = "agent1"))
            val agent2 = agentConfigRepo.save(AgentConfig(metadata = EntityMetadata(), namespaceId = ns.id, name = "agent2"))

            repo.deployAgents(ns.id, listOf(agent1.id, agent2.id))

            deployedToCount(ns.id.toString(), agent1.id.toString()) shouldBe 1
            deployedToCount(ns.id.toString(), agent2.id.toString()) shouldBe 1
        }

        "undeployAgents removes the DEPLOYED_TO relationship" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "ns"))
            val agent = agentConfigRepo.save(AgentConfig(metadata = EntityMetadata(), namespaceId = ns.id, name = "agent"))
            repo.deployAgents(ns.id, listOf(agent.id))

            repo.undeployAgents(ns.id, listOf(agent.id))

            val count = deployedToCount(ns.id.toString(), agent.id.toString())
            count shouldBe 0
        }

        "undeployAgents is a no-op when the relationship does not exist" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "ns"))
            val agent = agentConfigRepo.save(AgentConfig(metadata = EntityMetadata(), namespaceId = ns.id, name = "agent"))

            repo.undeployAgents(ns.id, listOf(agent.id))

            val count = deployedToCount(ns.id.toString(), agent.id.toString())
            count shouldBe 0
        }

        "undeployAgents removes only the requested relationships" {
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "ns"))
            val agent1 = agentConfigRepo.save(AgentConfig(metadata = EntityMetadata(), namespaceId = ns.id, name = "agent1"))
            val agent2 = agentConfigRepo.save(AgentConfig(metadata = EntityMetadata(), namespaceId = ns.id, name = "agent2"))
            repo.deployAgents(ns.id, listOf(agent1.id, agent2.id))

            repo.undeployAgents(ns.id, listOf(agent1.id))

            deployedToCount(ns.id.toString(), agent1.id.toString()) shouldBe 0
            deployedToCount(ns.id.toString(), agent2.id.toString()) shouldBe 1
        }
    }

    private fun deployedToCount(
        namespaceId: String,
        agentId: String,
    ): Long =
        driver.session().use { session ->
            session
                .run(
                    $$"MATCH (:AgentConfig {id: $agentId})-[:DEPLOYED_TO]->(:Namespace {id: $namespaceId}) RETURN count(*) AS c",
                    mapOf("agentId" to agentId, "namespaceId" to namespaceId),
                ).single()["c"]
                .asLong()
        }
}
