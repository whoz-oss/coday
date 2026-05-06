package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.dao.DataIntegrityViolationException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.userGroup.UserGroup
import io.whozoss.agentos.userGroup.UserGroupRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

abstract class AbstractUserGroupPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var userGroupRepo: UserGroupRepository
    @Autowired lateinit var namespaceRepo: NamespaceRepository
    @Autowired lateinit var userRepo: UserRepository
    @Autowired lateinit var driver: Driver

    fun namespace(externalId: String? = null) = Namespace(
        metadata = EntityMetadata(),
        name = "ns-${UUID.randomUUID()}",
        externalId = externalId,
    )

    fun userGroup(namespaceId: UUID, name: String) = UserGroup(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        name = name,
    )

    fun user(externalId: String) = User(
        metadata = EntityMetadata(),
        externalId = externalId,
        email = externalId,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "findByNamespaceExternalId returns groups belonging to the matching namespace" {
            val externalId = "federation-abc"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            userGroupRepo.save(userGroup(ns.id, "Group A"))
            userGroupRepo.save(userGroup(ns.id, "Group B"))

            val results = userGroupRepo.findByNamespaceExternalId(externalId)

            results shouldHaveSize 2
            results.map { it.name }.toSet() shouldBe setOf("Group A", "Group B")
            results.all { it.namespaceId == ns.id } shouldBe true
            results.all { it.namespaceExternalId == externalId } shouldBe true
        }

        "findByNamespaceExternalId returns groups ordered by name" {
            val externalId = "federation-order"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            userGroupRepo.save(userGroup(ns.id, "Zebra"))
            userGroupRepo.save(userGroup(ns.id, "Alpha"))
            userGroupRepo.save(userGroup(ns.id, "Mango"))

            val results = userGroupRepo.findByNamespaceExternalId(externalId)

            results.map { it.name } shouldBe listOf("Alpha", "Mango", "Zebra")
        }

        "findByNamespaceExternalId returns empty list for unknown externalId" {
            userGroupRepo.findByNamespaceExternalId("unknown-external-id").shouldBeEmpty()
        }

        "findByNamespaceExternalId does not return groups from other namespaces" {
            val ns1 = namespaceRepo.save(namespace(externalId = "fed-1"))
            val ns2 = namespaceRepo.save(namespace(externalId = "fed-2"))
            userGroupRepo.save(userGroup(ns1.id, "Group A"))
            userGroupRepo.save(userGroup(ns2.id, "Group B"))

            val results = userGroupRepo.findByNamespaceExternalId("fed-1")

            results shouldHaveSize 1
            results.first().name shouldBe "Group A"
        }

        "findByNamespaceExternalId does not return soft-deleted groups" {
            val externalId = "fed-delete"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            val g1 = userGroupRepo.save(userGroup(ns.id, "Keep"))
            val g2 = userGroupRepo.save(userGroup(ns.id, "Delete me"))
            userGroupRepo.delete(g2.id)

            val results = userGroupRepo.findByNamespaceExternalId(externalId)

            results shouldHaveSize 1
            results.first().name shouldBe "Keep"
        }

        "findByNamespaceExternalId returns empty list when namespace has no externalId" {
            val ns = namespaceRepo.save(namespace(externalId = null))
            userGroupRepo.save(userGroup(ns.id, "Orphan"))

            userGroupRepo.findByNamespaceExternalId("").shouldBeEmpty()
        }

        "save throws on duplicate name + namespaceId" {
            val ns = namespaceRepo.save(namespace())
            userGroupRepo.save(userGroup(ns.id, "Duplicate"))

            shouldThrow<DataIntegrityViolationException> {
                userGroupRepo.save(userGroup(ns.id, "Duplicate"))
            }
        }

        "same name in different namespaces is allowed" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            userGroupRepo.save(userGroup(ns1.id, "Team Alpha"))
            userGroupRepo.save(userGroup(ns2.id, "Team Alpha"))
        }

        "findByNamespaceExternalId returns userCount reflecting HAS_USER relations" {
            val externalId = "fed-usercount"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            val g = userGroupRepo.save(userGroup(ns.id, "Group With Users"))
            userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com", "bob@example.com"))

            val results = userGroupRepo.findByNamespaceExternalId(externalId)

            results shouldHaveSize 1
            results.first().userCount shouldBe 2
        }

        "findByNamespaceExternalId does not count soft-deleted users in userCount" {
            val externalId = "fed-usercount-deleted"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            val g = userGroupRepo.save(userGroup(ns.id, "Group With Deleted User"))
            val alice = userRepo.save(user("alice-del@example.com"))
            userRepo.save(user("bob-del@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice-del@example.com", "bob-del@example.com"))
            userRepo.delete(alice.id)

            val results = userGroupRepo.findByNamespaceExternalId(externalId)

            results shouldHaveSize 1
            results.first().userCount shouldBe 1
        }
    }
}
