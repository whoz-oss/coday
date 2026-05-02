package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.usergroup.UserGroup
import io.whozoss.agentos.usergroup.UserGroupRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

abstract class AbstractUserGroupPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var repo: UserGroupRepository
    @Autowired lateinit var userRepo: UserRepository
    @Autowired lateinit var agentConfigRepo: AgentConfigRepository
    @Autowired lateinit var driver: Driver

    private val namespaceId = UUID.randomUUID()

    private fun makeGroup(
        name: String = "Group A",
        nsId: UUID = namespaceId,
    ) = UserGroup(
        metadata = EntityMetadata(),
        namespaceId = nsId,
        name = name,
    )

    private fun makeUser(externalId: String = UUID.randomUUID().toString()) = User(
        metadata = EntityMetadata(),
        externalId = externalId,
        email = "$externalId@test.com",
    )

    private fun makeAgentConfig(name: String = "agent-${UUID.randomUUID()}") = AgentConfig(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        name = name,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same user group" {
            val group = makeGroup(name = "Engineering")
            val saved = repo.save(group)
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().name shouldBe "Engineering"
        }

        "findByParent returns groups for the given namespace" {
            repo.save(makeGroup(name = "A"))
            repo.save(makeGroup(name = "B"))
            val otherNs = UUID.randomUUID()
            repo.save(makeGroup(name = "C", nsId = otherNs))

            repo.findByParent(namespaceId) shouldHaveSize 2
            repo.findByParent(otherNs) shouldHaveSize 1
        }

        "soft delete removes group from findByIds" {
            val group = repo.save(makeGroup())
            repo.delete(group.id).shouldBeTrue()
            repo.findByIds(listOf(group.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val group = repo.save(makeGroup())
            repo.delete(group.id).shouldBeTrue()
            repo.delete(group.id).shouldBeFalse()
        }

        "deleteByParent removes all groups for the namespace" {
            repo.save(makeGroup(name = "A"))
            repo.save(makeGroup(name = "B"))
            val deleted = repo.deleteByParent(namespaceId)
            deleted shouldBe 2
            repo.findByParent(namespaceId).shouldBeEmpty()
        }

        "update: saving with same id replaces the node" {
            val group = repo.save(makeGroup(name = "original"))
            repo.save(group.copy(name = "updated"))
            val found = repo.findByIds(listOf(group.id))
            found shouldHaveSize 1
            found.first().name shouldBe "updated"
        }

        "addUser creates HAS_USER relationship" {
            val group = repo.save(makeGroup())
            val user = userRepo.save(makeUser())
            repo.addUser(group.id, user.id)
            repo.countUsers(group.id) shouldBe 1
        }

        "addUser is idempotent (MERGE)" {
            val group = repo.save(makeGroup())
            val user = userRepo.save(makeUser())
            repo.addUser(group.id, user.id)
            repo.addUser(group.id, user.id)
            repo.countUsers(group.id) shouldBe 1
        }

        "removeUser deletes HAS_USER relationship" {
            val group = repo.save(makeGroup())
            val user = userRepo.save(makeUser())
            repo.addUser(group.id, user.id)
            repo.removeUser(group.id, user.id)
            repo.countUsers(group.id) shouldBe 0
        }

        "countUsers counts only non-removed users" {
            val group = repo.save(makeGroup())
            val user1 = userRepo.save(makeUser("u1"))
            val user2 = userRepo.save(makeUser("u2"))
            repo.addUser(group.id, user1.id)
            repo.addUser(group.id, user2.id)
            repo.countUsers(group.id) shouldBe 2

            userRepo.delete(user1.id)
            repo.countUsers(group.id) shouldBe 1
        }

        "replaceAgents sets agent relationships" {
            val group = repo.save(makeGroup())
            val agent1 = agentConfigRepo.save(makeAgentConfig("a1"))
            val agent2 = agentConfigRepo.save(makeAgentConfig("a2"))
            repo.replaceAgents(group.id, setOf(agent1.id, agent2.id))

            repo.findAgentIds(group.id).shouldContainExactlyInAnyOrder(agent1.id, agent2.id)
            repo.countAgents(group.id) shouldBe 2
        }

        "replaceAgents replaces previous agents" {
            val group = repo.save(makeGroup())
            val agent1 = agentConfigRepo.save(makeAgentConfig("a1"))
            val agent2 = agentConfigRepo.save(makeAgentConfig("a2"))
            repo.replaceAgents(group.id, setOf(agent1.id))
            repo.replaceAgents(group.id, setOf(agent2.id))

            repo.findAgentIds(group.id) shouldBe listOf(agent2.id)
        }

        "replaceAgents with empty set clears all agents" {
            val group = repo.save(makeGroup())
            val agent = agentConfigRepo.save(makeAgentConfig())
            repo.replaceAgents(group.id, setOf(agent.id))
            repo.replaceAgents(group.id, emptySet())

            repo.findAgentIds(group.id).shouldBeEmpty()
            repo.countAgents(group.id) shouldBe 0
        }

        "countAgents counts only non-removed agents" {
            val group = repo.save(makeGroup())
            val agent1 = agentConfigRepo.save(makeAgentConfig("a1"))
            val agent2 = agentConfigRepo.save(makeAgentConfig("a2"))
            repo.replaceAgents(group.id, setOf(agent1.id, agent2.id))
            repo.countAgents(group.id) shouldBe 2

            agentConfigRepo.delete(agent1.id)
            repo.countAgents(group.id) shouldBe 1
        }

        "softDeleteWithRelationships removes group and all relationships" {
            val group = repo.save(makeGroup())
            val user = userRepo.save(makeUser())
            val agent = agentConfigRepo.save(makeAgentConfig())
            repo.addUser(group.id, user.id)
            repo.replaceAgents(group.id, setOf(agent.id))

            repo.softDeleteWithRelationships(group.id)

            repo.findByIds(listOf(group.id)).shouldBeEmpty()
            repo.findAgentIds(group.id).shouldBeEmpty()
            repo.countUsers(group.id) shouldBe 0
        }
    }
}
