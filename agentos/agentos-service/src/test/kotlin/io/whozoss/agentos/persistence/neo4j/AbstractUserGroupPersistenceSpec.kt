package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.userGroup.UserGroup
import io.whozoss.agentos.userGroup.UserGroupMember
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

        // -------------------------------------------------------------------------
        // findByIdWithDetails — member list with roles
        // -------------------------------------------------------------------------

        "findByIdWithDetails returns empty members list when group has no members" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-detail-empty"))
            val g = userGroupRepo.save(userGroup(ns.id, "Empty Group"))

            val result = userGroupRepo.findByIdWithDetails(g.id)

            result!!.members.shouldBeEmpty()
            result.userCount shouldBe 0
        }

        "findByIdWithDetails returns members with MEMBER role added via addUsers" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-detail-member"))
            val g = userGroupRepo.save(userGroup(ns.id, "Group Detail"))
            val alice = userRepo.save(user("alice-detail@example.com"))
            userGroupRepo.addUsers(g.id, listOf(alice.externalId))

            val result = userGroupRepo.findByIdWithDetails(g.id)!!

            result.members shouldHaveSize 1
            result.userCount shouldBe 1
            result.members.first().let {
                it.userId shouldBe alice.id
                it.externalId shouldBe alice.externalId
                it.role shouldBe UserGroupMember.ROLE_MEMBER
            }
        }

        "findByIdWithDetails does not return soft-deleted members" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-detail-del"))
            val g = userGroupRepo.save(userGroup(ns.id, "Group Detail Del"))
            val alice = userRepo.save(user("alice-dd@example.com"))
            val bob = userRepo.save(user("bob-dd@example.com"))
            userGroupRepo.addUsers(g.id, listOf(alice.externalId, bob.externalId))
            userRepo.delete(alice.id)

            val result = userGroupRepo.findByIdWithDetails(g.id)!!

            result.members shouldHaveSize 1
            result.members.first().externalId shouldBe bob.externalId
            result.userCount shouldBe 1
        }

        // -------------------------------------------------------------------------
        // updateMemberships
        // -------------------------------------------------------------------------

        "updateMemberships grants ADMIN role and revokes any existing MEMBER" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-upsert-admin"))
            val g = userGroupRepo.save(userGroup(ns.id, "Upsert Admin"))
            val alice = userRepo.save(user("alice-ua@example.com"))
            // Start as MEMBER via legacy sync
            userGroupRepo.addUsers(g.id, listOf(alice.externalId))

            userGroupRepo.updateMemberships(g.id, listOf(alice.id to UserGroupMember.ROLE_ADMIN))

            val result = userGroupRepo.findByIdWithDetails(g.id)!!
            result.members shouldHaveSize 1
            result.members.first().role shouldBe UserGroupMember.ROLE_ADMIN
        }

        "updateMemberships demotes ADMIN to MEMBER" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-upsert-demote"))
            val g = userGroupRepo.save(userGroup(ns.id, "Upsert Demote"))
            val alice = userRepo.save(user("alice-ud@example.com"))
            userGroupRepo.updateMemberships(g.id, listOf(alice.id to UserGroupMember.ROLE_ADMIN))

            userGroupRepo.updateMemberships(g.id, listOf(alice.id to UserGroupMember.ROLE_MEMBER))

            val result = userGroupRepo.findByIdWithDetails(g.id)!!
            result.members shouldHaveSize 1
            result.members.first().role shouldBe UserGroupMember.ROLE_MEMBER
        }

        "updateMemberships with null role removes user from group" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-upsert-remove"))
            val g = userGroupRepo.save(userGroup(ns.id, "Upsert Remove"))
            val alice = userRepo.save(user("alice-ur@example.com"))
            userGroupRepo.updateMemberships(g.id, listOf(alice.id to UserGroupMember.ROLE_MEMBER))

            userGroupRepo.updateMemberships(g.id, listOf(alice.id to null))

            val result = userGroupRepo.findByIdWithDetails(g.id)!!
            result.members.shouldBeEmpty()
        }

        "updateMemberships is idempotent for MEMBER" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-upsert-idem"))
            val g = userGroupRepo.save(userGroup(ns.id, "Upsert Idempotent"))
            val alice = userRepo.save(user("alice-ui@example.com"))
            userGroupRepo.updateMemberships(g.id, listOf(alice.id to UserGroupMember.ROLE_MEMBER))
            userGroupRepo.updateMemberships(g.id, listOf(alice.id to UserGroupMember.ROLE_MEMBER))

            val result = userGroupRepo.findByIdWithDetails(g.id)!!
            result.members shouldHaveSize 1
            result.members.first().role shouldBe UserGroupMember.ROLE_MEMBER
        }

        "updateMemberships silently skips unknown userIds" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-upsert-unknown"))
            val g = userGroupRepo.save(userGroup(ns.id, "Upsert Unknown"))

            userGroupRepo.updateMemberships(g.id, listOf(UUID.randomUUID() to UserGroupMember.ROLE_MEMBER))

            val result = userGroupRepo.findByIdWithDetails(g.id)!!
            result.members.shouldBeEmpty()
        }

        "findByNamespaceId counts both ADMIN and MEMBER edges" {
            val ns = namespaceRepo.save(namespace(externalId = "fed-count-both"))
            val g = userGroupRepo.save(userGroup(ns.id, "Count Both"))
            val alice = userRepo.save(user("alice-cb@example.com"))
            val bob = userRepo.save(user("bob-cb@example.com"))
            userGroupRepo.updateMemberships(g.id, listOf(
                alice.id to UserGroupMember.ROLE_ADMIN,
                bob.id to UserGroupMember.ROLE_MEMBER,
            ))

            val results = userGroupRepo.findByNamespaceId(ns.id)

            results shouldHaveSize 1
            results.first().userCount shouldBe 2
        }
    }
}
