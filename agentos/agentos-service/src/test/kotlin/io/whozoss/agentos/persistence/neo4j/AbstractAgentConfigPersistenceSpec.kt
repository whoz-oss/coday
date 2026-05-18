package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
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

/**
 * Persistence contract tests for [AgentConfigRepository.findAvailableByUserExternalId].
 *
 * Verifies the two resolution paths and their union/deduplication semantics:
 * 1. Agents deployed on a [UserGroup] the user is a member of
 * 2. Agents deployed on a [Namespace] the user has a MEMBER or ADMIN relation on
 */
abstract class AbstractAgentConfigPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var agentConfigRepo: AgentConfigRepository
    @Autowired lateinit var namespaceRepo: NamespaceRepository
    @Autowired lateinit var userGroupRepo: UserGroupRepository
    @Autowired lateinit var userRepo: UserRepository
    @Autowired lateinit var driver: Driver

    private fun namespace(name: String = "ns-${UUID.randomUUID()}") =
        Namespace(metadata = EntityMetadata(), name = name)

    private fun agentConfig(namespaceId: UUID, name: String) =
        AgentConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = name)

    private fun user(externalId: String) =
        User(metadata = EntityMetadata(), externalId = externalId, email = externalId)

    private fun userGroup(namespaceId: UUID, name: String = "group-${UUID.randomUUID()}") =
        UserGroup(metadata = EntityMetadata(), namespaceId = namespaceId, name = name)

    private fun grantMember(userId: String, namespaceId: String) =
        driver.session().use { session ->
            session.run(
                "MATCH (u:User {externalId: \$userId}) MATCH (n:Namespace {id: \$nsId}) MERGE (u)-[:MEMBER]->(n)",
                mapOf("userId" to userId, "nsId" to namespaceId),
            )
        }

    private fun grantAdmin(userId: String, namespaceId: String) =
        driver.session().use { session ->
            session.run(
                "MATCH (u:User {externalId: \$userId}) MATCH (n:Namespace {id: \$nsId}) MERGE (u)-[:ADMIN]->(n)",
                mapOf("userId" to userId, "nsId" to namespaceId),
            )
        }

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // -------------------------------------------------------------------------
        // No membership — empty result
        // -------------------------------------------------------------------------

        "returns empty list for unknown userExternalId" {
            agentConfigRepo.findAvailableByUserExternalId("ghost@example.com").shouldBeEmpty()
        }

        "returns empty list for user with no group and no namespace membership" {
            val ns = namespaceRepo.save(namespace())
            userRepo.save(user("alice@example.com"))
            agentConfigRepo.save(agentConfig(ns.id, "agent-a"))
            // no DEPLOYED_TO, no group, no namespace relation

            agentConfigRepo.findAvailableByUserExternalId("alice@example.com").shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Path 1: agents deployed on a UserGroup the user is a member of
        // -------------------------------------------------------------------------

        "returns agents deployed on user group the user is a member of" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "group-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByUserExternalId("alice@example.com")

            result shouldHaveSize 1
            result.first().name shouldBe "group-agent"
        }

        "does not return agents deployed on a group the user is not a member of" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "other-group-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("bob@example.com"))

            agentConfigRepo.findAvailableByUserExternalId("alice@example.com").shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Path 2: agents deployed on a Namespace the user has MEMBER/ADMIN on
        // -------------------------------------------------------------------------

        "returns agents deployed on a namespace the user has MEMBER relation on" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "ns-agent"))
            userRepo.save(user("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(agent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByUserExternalId("alice@example.com")

            result shouldHaveSize 1
            result.first().name shouldBe "ns-agent"
        }

        "returns agents deployed on a namespace the user has ADMIN relation on" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "admin-ns-agent"))
            userRepo.save(user("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(agent.id))
            grantAdmin("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByUserExternalId("alice@example.com")

            result shouldHaveSize 1
            result.first().name shouldBe "admin-ns-agent"
        }

        "does not return agents on a namespace the user has no relation to" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "unreachable-agent"))
            userRepo.save(user("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(agent.id))
            // alice has no MEMBER/ADMIN on ns

            agentConfigRepo.findAvailableByUserExternalId("alice@example.com").shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Union and deduplication
        // -------------------------------------------------------------------------

        "deduplicates agent deployed on two groups the user is a member of" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "shared-agent"))
            val group1 = userGroupRepo.save(userGroup(ns.id))
            val group2 = userGroupRepo.save(userGroup(ns.id))
            userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group1.id, listOf(agent.id))
            userGroupRepo.addAgents(group2.id, listOf(agent.id))
            userGroupRepo.addUsers(group1.id, listOf("alice@example.com"))
            userGroupRepo.addUsers(group2.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByUserExternalId("alice@example.com")

            result shouldHaveSize 1
            result.first().name shouldBe "shared-agent"
        }

        "deduplicates agents reachable via both group and namespace paths" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "shared-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userRepo.save(user("alice@example.com"))
            // deploy on both the group and the namespace
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(agent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByUserExternalId("alice@example.com")

            result shouldHaveSize 1
            result.first().name shouldBe "shared-agent"
        }

        "returns union of agents from multiple groups and namespaces" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            val groupAgent = agentConfigRepo.save(agentConfig(ns1.id, "group-agent"))
            val nsAgent = agentConfigRepo.save(agentConfig(ns2.id, "ns-agent"))
            val group = userGroupRepo.save(userGroup(ns1.id))
            userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(groupAgent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            namespaceRepo.deployAgents(ns2.id, listOf(nsAgent.id))
            grantMember("alice@example.com", ns2.id.toString())

            val result = agentConfigRepo.findAvailableByUserExternalId("alice@example.com")

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("group-agent", "ns-agent")
        }

        // -------------------------------------------------------------------------
        // Soft-delete filtering
        // -------------------------------------------------------------------------

        "does not return soft-deleted agents" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "deleted-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            agentConfigRepo.delete(agent.id)

            agentConfigRepo.findAvailableByUserExternalId("alice@example.com").shouldBeEmpty()
        }
    }
}
