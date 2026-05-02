package io.whozoss.agentos.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.usergroup.InMemoryUserGroupRepository
import io.whozoss.agentos.usergroup.UserGroup
import java.util.UUID

class UserGroupPersistenceLifecycleSpec :
    StringSpec({

        val namespaceId = UUID.randomUUID()

        fun makeGroup(
            name: String = "Group A",
            nsId: UUID = namespaceId,
        ) = UserGroup(
            metadata = EntityMetadata(),
            namespaceId = nsId,
            name = name,
        )

        "full CRUD lifecycle: create, read, update, delete" {
            val repo = InMemoryUserGroupRepository()

            val group = makeGroup(name = "Engineering")
            val created = repo.save(group)
            created.name shouldBe "Engineering"

            val found = repo.findByIds(listOf(created.metadata.id))
            found shouldHaveSize 1
            found.first().name shouldBe "Engineering"

            val updated = created.copy(name = "Engineering v2")
            repo.save(updated)
            val afterUpdate = repo.findByIds(listOf(created.metadata.id)).first()
            afterUpdate.name shouldBe "Engineering v2"

            repo.delete(created.metadata.id).shouldBeTrue()
            repo.findByIds(listOf(created.metadata.id)).shouldBeEmpty()
            repo.findByParent(namespaceId).shouldBeEmpty()
        }

        "findByParent returns only groups for the given namespace" {
            val repo = InMemoryUserGroupRepository()
            val otherNamespace = UUID.randomUUID()

            repo.save(makeGroup(name = "A", nsId = namespaceId))
            repo.save(makeGroup(name = "B", nsId = namespaceId))
            repo.save(makeGroup(name = "C", nsId = otherNamespace))

            repo.findByParent(namespaceId) shouldHaveSize 2
            repo.findByParent(otherNamespace) shouldHaveSize 1
        }

        "multiple groups are all retrievable" {
            val repo = InMemoryUserGroupRepository()
            val names = listOf("Alpha", "Beta", "Gamma")
            val ids = names.map { repo.save(makeGroup(name = it)).metadata.id }

            val found = repo.findByParent(namespaceId)
            found shouldHaveSize 3
            found.map { it.metadata.id }.containsAll(ids).shouldBeTrue()
        }

        "deleting an already-deleted group returns false" {
            val repo = InMemoryUserGroupRepository()
            val group = repo.save(makeGroup())

            repo.delete(group.metadata.id).shouldBeTrue()
            repo.delete(group.metadata.id).shouldBeFalse()
        }

        "deleteByParent removes all groups for the namespace" {
            val repo = InMemoryUserGroupRepository()
            repo.save(makeGroup(name = "A"))
            repo.save(makeGroup(name = "B"))
            repo.save(makeGroup(name = "C"))

            val deleted = repo.deleteByParent(namespaceId)
            deleted shouldBe 3
            repo.findByParent(namespaceId).shouldBeEmpty()
        }

        "stable identity after creation" {
            val repo = InMemoryUserGroupRepository()
            val fixedId = UUID.randomUUID()
            repo.save(
                UserGroup(
                    metadata = EntityMetadata(id = fixedId),
                    namespaceId = namespaceId,
                    name = "stable-group",
                ),
            )

            val found = repo.findByIds(listOf(fixedId)).first()
            found.metadata.id shouldBe fixedId
            found.name shouldBe "stable-group"
        }

        "addUser and removeUser manage user relationships" {
            val repo = InMemoryUserGroupRepository()
            val group = repo.save(makeGroup())
            val userId1 = UUID.randomUUID()
            val userId2 = UUID.randomUUID()

            repo.addUser(group.id, userId1)
            repo.addUser(group.id, userId2)
            repo.addUser(group.id, userId1)

            repo.removeUser(group.id, userId1)
        }

        "findAgentIds returns empty list for group with no agents" {
            val repo = InMemoryUserGroupRepository()
            val group = repo.save(makeGroup())

            repo.findAgentIds(group.id).shouldBeEmpty()
        }

        "replaceAgents sets agent ids" {
            val repo = InMemoryUserGroupRepository()
            val group = repo.save(makeGroup())
            val agent1 = UUID.randomUUID()
            val agent2 = UUID.randomUUID()

            repo.replaceAgents(group.id, setOf(agent1, agent2))
            repo.findAgentIds(group.id).shouldContainExactlyInAnyOrder(agent1, agent2)

            val agent3 = UUID.randomUUID()
            repo.replaceAgents(group.id, setOf(agent3))
            repo.findAgentIds(group.id) shouldBe listOf(agent3)
        }

        "replaceAgents with empty set clears all agents" {
            val repo = InMemoryUserGroupRepository()
            val group = repo.save(makeGroup())
            repo.replaceAgents(group.id, setOf(UUID.randomUUID()))

            repo.replaceAgents(group.id, emptySet())
            repo.findAgentIds(group.id).shouldBeEmpty()
        }

        "softDeleteWithRelationships removes group and all relationships" {
            val repo = InMemoryUserGroupRepository()
            val group = repo.save(makeGroup())
            val userId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            repo.addUser(group.id, userId)
            repo.replaceAgents(group.id, setOf(agentId))

            repo.softDeleteWithRelationships(group.id)

            repo.findByIds(listOf(group.id)).shouldBeEmpty()
            repo.findAgentIds(group.id).shouldBeEmpty()
        }

        "countUsers returns the number of users in the group" {
            val repo = InMemoryUserGroupRepository()
            val group = repo.save(makeGroup())
            val userId1 = UUID.randomUUID()
            val userId2 = UUID.randomUUID()

            repo.countUsers(group.id) shouldBe 0
            repo.addUser(group.id, userId1)
            repo.countUsers(group.id) shouldBe 1
            repo.addUser(group.id, userId2)
            repo.countUsers(group.id) shouldBe 2
            repo.removeUser(group.id, userId1)
            repo.countUsers(group.id) shouldBe 1
        }

        "countAgents returns the number of agents in the group" {
            val repo = InMemoryUserGroupRepository()
            val group = repo.save(makeGroup())
            val agentId1 = UUID.randomUUID()
            val agentId2 = UUID.randomUUID()

            repo.countAgents(group.id) shouldBe 0
            repo.replaceAgents(group.id, setOf(agentId1, agentId2))
            repo.countAgents(group.id) shouldBe 2
            repo.replaceAgents(group.id, emptySet())
            repo.countAgents(group.id) shouldBe 0
        }
    })
