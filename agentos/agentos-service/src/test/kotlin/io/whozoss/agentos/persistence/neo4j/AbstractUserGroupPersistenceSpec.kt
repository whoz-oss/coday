package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.userGroup.UserGroup
import io.whozoss.agentos.userGroup.UserGroupRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.util.*

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

        "findByNamespaceId returns groups belonging to the matching namespace" {
            val externalId = "f9db0e73-22de-49b4-a216-6f20c11418fe"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            userGroupRepo.save(userGroup(ns.id, "Group A"))
            userGroupRepo.save(userGroup(ns.id, "Group B"))

            val results = userGroupRepo.findByNamespaceId(ns.id)

            results shouldHaveSize 2
            results.map { it.name }.toSet() shouldBe setOf("Group A", "Group B")
            results.all { it.namespaceId == ns.id } shouldBe true
            results.all { it.namespaceExternalId == externalId } shouldBe true
        }

        "findByNamespaceId returns groups ordered by name" {
            val externalId = "federation-order"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            userGroupRepo.save(userGroup(ns.id, "Zebra"))
            userGroupRepo.save(userGroup(ns.id, "Alpha"))
            userGroupRepo.save(userGroup(ns.id, "Mango"))

            val results = userGroupRepo.findByNamespaceId(ns.id)

            results.map { it.name } shouldBe listOf("Alpha", "Mango", "Zebra")
        }

        "findByNamespaceId returns empty list for unknown externalId" {
            userGroupRepo.findByNamespaceId(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByNamespaceId does not return groups from other namespaces" {
            val ns1 = namespaceRepo.save(namespace(externalId = "fed-1"))
            val ns2 = namespaceRepo.save(namespace(externalId = "fed-2"))
            userGroupRepo.save(userGroup(ns1.id, "Group A"))
            userGroupRepo.save(userGroup(ns2.id, "Group B"))

            val results = userGroupRepo.findByNamespaceId(ns1.id)

            results shouldHaveSize 1
            results.first().name shouldBe "Group A"
        }

        "findByNamespaceId does not return soft-deleted groups" {
            val externalId = "fed-delete"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            val g1 = userGroupRepo.save(userGroup(ns.id, "Keep"))
            val g2 = userGroupRepo.save(userGroup(ns.id, "Delete me"))
            userGroupRepo.delete(g2.id)

            val results = userGroupRepo.findByNamespaceId(ns.id)

            results shouldHaveSize 1
            results.first().name shouldBe "Keep"
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

        "findByNamespaceId returns userCount reflecting MEMBER relations" {
            val externalId = "fed-usercount"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            val g = userGroupRepo.save(userGroup(ns.id, "Group With Users"))
            userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com", "bob@example.com"))

            val results = userGroupRepo.findByNamespaceId(ns.id)

            results shouldHaveSize 1
            results.first().userCount shouldBe 2
        }

        "findByNamespaceId does not count soft-deleted users in userCount" {
            val externalId = "fed-usercount-deleted"
            val ns = namespaceRepo.save(namespace(externalId = externalId))
            val g = userGroupRepo.save(userGroup(ns.id, "Group With Deleted User"))
            val alice = userRepo.save(user("alice-del@example.com"))
            userRepo.save(user("bob-del@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice-del@example.com", "bob-del@example.com"))
            userRepo.delete(alice.id)

            val results = userGroupRepo.findByNamespaceId(ns.id)

            results shouldHaveSize 1
            results.first().userCount shouldBe 1
        }

        "findGroupsByUserExternalIds returns groups for matching users" {
            val ns = namespaceRepo.save(namespace())
            val g = userGroupRepo.save(userGroup(ns.id, "Devs"))
            userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com", "bob@example.com"))

            val results = userGroupRepo.findGroupsByUserExternalIds(
                externalIds = listOf("alice@example.com", "bob@example.com"),
                namespaceId = null,
            )

            results["alice@example.com"]?.shouldHaveSize(1)
            results["alice@example.com"]!!.first().name shouldBe "Devs"
            results["bob@example.com"]?.shouldHaveSize(1)
        }

        "findGroupsByUserExternalIds scoped to namespaceId excludes other namespaces" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            val g1 = userGroupRepo.save(userGroup(ns1.id, "Group NS1"))
            val g2 = userGroupRepo.save(userGroup(ns2.id, "Group NS2"))
            userRepo.save(user("carol@example.com"))
            userGroupRepo.addUsers(g1.id, listOf("carol@example.com"))
            userGroupRepo.addUsers(g2.id, listOf("carol@example.com"))

            val results = userGroupRepo.findGroupsByUserExternalIds(
                externalIds = listOf("carol@example.com"),
                namespaceId = ns1.id,
            )

            results["carol@example.com"]?.shouldHaveSize(1)
            results["carol@example.com"]!!.first().name shouldBe "Group NS1"
        }

        "findGroupsByUserExternalIds returns empty map for empty input" {
            userGroupRepo.findGroupsByUserExternalIds(
                externalIds = emptyList(),
                namespaceId = null,
            ).shouldBeEmpty()
        }

        "findMembers returns members ordered by externalId with display fields and MEMBER role" {
            val ns = namespaceRepo.save(namespace())
            val g = userGroupRepo.save(userGroup(ns.id, "Group"))
            userRepo.save(user("bob@example.com").copy(firstname = "Bob"))
            userRepo.save(user("alice@example.com").copy(firstname = "Alice", lastname = "Adams"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com", "bob@example.com"))

            val members = userGroupRepo.findMembers(g.id)

            members.map { it.externalId } shouldBe listOf("alice@example.com", "bob@example.com")
            members.first().firstname shouldBe "Alice"
            members.first().lastname shouldBe "Adams"
            members.first().email shouldBe "alice@example.com"
            members.all { it.role == "MEMBER" } shouldBe true
        }

        "findMembers excludes soft-deleted users" {
            val ns = namespaceRepo.save(namespace())
            val g = userGroupRepo.save(userGroup(ns.id, "Group"))
            val alice = userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com", "bob@example.com"))
            userRepo.delete(alice.id)

            userGroupRepo.findMembers(g.id).map { it.externalId } shouldBe listOf("bob@example.com")
        }

        "findMembers returns empty for a group with no members" {
            val ns = namespaceRepo.save(namespace())
            val g = userGroupRepo.save(userGroup(ns.id, "Empty"))

            userGroupRepo.findMembers(g.id).shouldBeEmpty()
        }

        "setMemberRoles promotes members to ADMIN and findMembers reflects it" {
            val ns = namespaceRepo.save(namespace())
            val g = userGroupRepo.save(userGroup(ns.id, "Group"))
            userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com", "bob@example.com"))

            userGroupRepo.setMemberRoles(g.id, listOf("alice@example.com"))

            val byExternalId = userGroupRepo.findMembers(g.id).associateBy { it.externalId }
            byExternalId["alice@example.com"]!!.role shouldBe "ADMIN"
            byExternalId["bob@example.com"]!!.role shouldBe "MEMBER"
        }

        "setMemberRoles demotes an admin no longer in the set" {
            val ns = namespaceRepo.save(namespace())
            val g = userGroupRepo.save(userGroup(ns.id, "Group"))
            userRepo.save(user("alice@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com"))
            userGroupRepo.setMemberRoles(g.id, listOf("alice@example.com"))
            userGroupRepo.findMembers(g.id).first().role shouldBe "ADMIN"

            userGroupRepo.setMemberRoles(g.id, emptyList())

            userGroupRepo.findMembers(g.id).first().role shouldBe "MEMBER"
        }

        "setMemberRoles ignores ids that are not members" {
            val ns = namespaceRepo.save(namespace())
            val g = userGroupRepo.save(userGroup(ns.id, "Group"))
            userRepo.save(user("alice@example.com"))
            userRepo.save(user("stranger@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com"))

            userGroupRepo.setMemberRoles(g.id, listOf("stranger@example.com"))

            val members = userGroupRepo.findMembers(g.id)
            members.map { it.externalId } shouldBe listOf("alice@example.com")
            members.first().role shouldBe "MEMBER"
        }

        "removeUsers unlinks a member whatever their role" {
            val ns = namespaceRepo.save(namespace())
            val g = userGroupRepo.save(userGroup(ns.id, "Group"))
            userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addUsers(g.id, listOf("alice@example.com", "bob@example.com"))
            userGroupRepo.setMemberRoles(g.id, listOf("alice@example.com"))

            userGroupRepo.removeUsers(g.id, listOf("alice@example.com"))

            userGroupRepo.findMembers(g.id).map { it.externalId } shouldBe listOf("bob@example.com")
        }
    }
}
