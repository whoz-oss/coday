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
import io.whozoss.agentos.config.TestAuditConfiguration
import io.whozoss.agentos.userGroup.UserGroupRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Persistence contract tests for [AgentConfigRepository] Cypher queries.
 *
 * Covers:
 * - [AgentConfigRepository.findByParent] with `enabledOnly` filter (enabled/disabled,
 *   backward compatibility with null enabled, soft-delete, namespace scoping)
 * - [AgentConfigRepository.findAvailableByNamespaceIdAndUserId] (user group membership,
 *   namespace membership, union/deduplication, agent name filtering)
 */
abstract class AbstractAgentConfigPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var agentConfigRepo: AgentConfigRepository
    @Autowired lateinit var namespaceRepo: NamespaceRepository
    @Autowired lateinit var userGroupRepo: UserGroupRepository
    @Autowired lateinit var userRepo: UserRepository
    @Autowired lateinit var driver: Driver

    private fun namespace(externalId: String = "ext-${UUID.randomUUID()}") =
        Namespace(metadata = EntityMetadata(), name = "ns-$externalId", externalId = externalId)

    private fun agentConfig(namespaceId: UUID, name: String) =
        AgentConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = name)

    private fun user(externalId: String) =
        User(metadata = EntityMetadata(), externalId = externalId, email = externalId)

    private fun userGroup(namespaceId: UUID, name: String = "group-${UUID.randomUUID()}") =
        UserGroup(metadata = EntityMetadata(), namespaceId = namespaceId, name = name)

    private fun grantMember(userExternalId: String, namespaceId: String) =
        driver.session().use { session ->
            session.run(
                "MATCH (u:User {externalId: \$userId}) MATCH (n:Namespace {id: \$nsId}) MERGE (u)-[:MEMBER]->(n)",
                mapOf("userId" to userExternalId, "nsId" to namespaceId),
            )
        }

    private fun grantAdmin(userExternalId: String, namespaceId: String) =
        driver.session().use { session ->
            session.run(
                "MATCH (u:User {externalId: \$userId}) MATCH (n:Namespace {id: \$nsId}) MERGE (u)-[:ADMIN]->(n)",
                mapOf("userId" to userExternalId, "nsId" to namespaceId),
            )
        }

    init {
        beforeEach {
            Neo4jContainerSupport.clearDatabase(driver)
            TestAuditConfiguration.currentAuditorId = TestAuditConfiguration.TEST_AUDITOR_ID
        }

        // -------------------------------------------------------------------------
        // No membership — empty result
        // -------------------------------------------------------------------------

        "returns empty list for unknown userId" {
            val ns = namespaceRepo.save(namespace())
            val ghost = userRepo.save(user("ghost@example.com"))
            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, ghost.id, null).shouldBeEmpty()
        }

        "returns empty list for user with no group and no namespace membership" {
            val ns = namespaceRepo.save(namespace())
            val alice = userRepo.save(user("alice@example.com"))
            agentConfigRepo.save(agentConfig(ns.id, "agent-a"))
            // no DEPLOYED_TO, no group, no namespace relation

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Path 1: agents deployed on a UserGroup (in the namespace) the user is a member of
        // -------------------------------------------------------------------------

        "returns agents deployed on user group the user is a member of" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "group-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result shouldHaveSize 1
            result.first().name shouldBe "group-agent"
        }

        "does not return agents deployed on a group the user is not a member of" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "other-group-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("bob@example.com"))

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        "does not return agents from a group belonging to a different namespace" {
            val ns = namespaceRepo.save(namespace())
            val otherNs = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(otherNs.id, "other-ns-group-agent"))
            val group = userGroupRepo.save(userGroup(otherNs.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))

            // alice is in a group with an agent, but that group belongs to otherNs, not ns
            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Path 2: agents deployed directly on the namespace
        // -------------------------------------------------------------------------

        "returns agents deployed on a namespace the user has MEMBER relation on" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "ns-agent"))
            val alice = userRepo.save(user("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(agent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result shouldHaveSize 1
            result.first().name shouldBe "ns-agent"
        }

        "returns agents deployed on a namespace the user has ADMIN relation on" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "admin-ns-agent"))
            val alice = userRepo.save(user("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(agent.id))
            grantAdmin("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result shouldHaveSize 1
            result.first().name shouldBe "admin-ns-agent"
        }

        "does not return agents on a namespace the user has no relation to" {
            val ns = namespaceRepo.save(namespace())
            agentConfigRepo.save(agentConfig(ns.id, "unreachable-agent"))
            val alice = userRepo.save(user("alice@example.com"))
            // alice has no MEMBER/ADMIN on ns

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        "does not return agents deployed on a different namespace" {
            val ns = namespaceRepo.save(namespace())
            val otherNs = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(otherNs.id, "other-ns-agent"))
            val alice = userRepo.save(user("alice@example.com"))
            namespaceRepo.deployAgents(otherNs.id, listOf(agent.id))
            grantMember("alice@example.com", otherNs.id.toString())

            // querying ns, not otherNs
            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Union and deduplication
        // -------------------------------------------------------------------------

        "deduplicates agent deployed on two groups the user is a member of" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "shared-agent"))
            val group1 = userGroupRepo.save(userGroup(ns.id))
            val group2 = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group1.id, listOf(agent.id))
            userGroupRepo.addAgents(group2.id, listOf(agent.id))
            userGroupRepo.addUsers(group1.id, listOf("alice@example.com"))
            userGroupRepo.addUsers(group2.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result shouldHaveSize 1
            result.first().name shouldBe "shared-agent"
        }

        "deduplicates agents reachable via both group and namespace paths" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "shared-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(agent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result shouldHaveSize 1
            result.first().name shouldBe "shared-agent"
        }

        "returns union of agents from group and namespace deployments" {
            val ns = namespaceRepo.save(namespace())
            val groupAgent = agentConfigRepo.save(agentConfig(ns.id, "group-agent"))
            val nsAgent = agentConfigRepo.save(agentConfig(ns.id, "ns-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(groupAgent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(nsAgent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("group-agent", "ns-agent")
        }

        // -------------------------------------------------------------------------
        // Soft-delete filtering
        // -------------------------------------------------------------------------

        "does not return soft-deleted agents" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "deleted-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            agentConfigRepo.delete(agent.id)

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findByParent with enabledOnly filter
        // -------------------------------------------------------------------------

        "findByParent with enabledOnly=false returns all active configs regardless of enabled status" {
            val ns = namespaceRepo.save(namespace())
            val enabled = agentConfigRepo.save(agentConfig(ns.id, "enabled-agent").copy(enabled = true))
            val disabled = agentConfigRepo.save(agentConfig(ns.id, "disabled-agent").copy(enabled = false))

            val result = agentConfigRepo.findByParent(ns.id, enabledOnly = false)

            result.map { it.id } shouldContainExactlyInAnyOrder listOf(enabled.id, disabled.id)
        }

        "findByParent with enabledOnly=true returns only enabled configs" {
            val ns = namespaceRepo.save(namespace())
            val enabled = agentConfigRepo.save(agentConfig(ns.id, "enabled-agent").copy(enabled = true))
            agentConfigRepo.save(agentConfig(ns.id, "disabled-agent").copy(enabled = false))

            val result = agentConfigRepo.findByParent(ns.id, enabledOnly = true)

            result shouldHaveSize 1
            result.first().id shouldBe enabled.id
        }

        "findByParent with enabledOnly=true treats null enabled as disabled" {
            val ns = namespaceRepo.save(namespace())
            val saved = agentConfigRepo.save(agentConfig(ns.id, "legacy-agent"))
            driver.session().use { session ->
                session.run(
                    "MATCH (a:AgentConfig {id: \$id}) REMOVE a.enabled",
                    mapOf("id" to saved.id.toString()),
                )
            }

            val result = agentConfigRepo.findByParent(ns.id, enabledOnly = true)

            result.shouldBeEmpty()
        }

        "findByParent excludes soft-deleted configs" {
            val ns = namespaceRepo.save(namespace())
            val active = agentConfigRepo.save(agentConfig(ns.id, "active-agent"))
            val toDelete = agentConfigRepo.save(agentConfig(ns.id, "deleted-agent"))
            agentConfigRepo.delete(toDelete.id)

            val result = agentConfigRepo.findByParent(ns.id, enabledOnly = false)

            result shouldHaveSize 1
            result.first().id shouldBe active.id
        }

        "findByParent with enabledOnly=true excludes soft-deleted enabled configs" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "enabled-then-deleted").copy(enabled = true))
            agentConfigRepo.delete(agent.id)

            val result = agentConfigRepo.findByParent(ns.id, enabledOnly = true)

            result.shouldBeEmpty()
        }

        "findByParent returns configs scoped to the given namespace only" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            val agentInNs1 = agentConfigRepo.save(agentConfig(ns1.id, "ns1-agent"))
            agentConfigRepo.save(agentConfig(ns2.id, "ns2-agent"))

            val result = agentConfigRepo.findByParent(ns1.id, enabledOnly = false)

            result shouldHaveSize 1
            result.first().id shouldBe agentInNs1.id
        }

        // -------------------------------------------------------------------------
        // findAvailableByNamespaceIdAndUserId — agentName = null (all accessible)
        // -------------------------------------------------------------------------

        "findAvailableByNamespaceIdAndUserId with null agentName returns all accessible agents via group path" {
            val ns = namespaceRepo.save(namespace())
            val user = userRepo.save(user("alice@example.com"))
            val agentA = agentConfigRepo.save(agentConfig(ns.id, "agent-a"))
            val agentB = agentConfigRepo.save(agentConfig(ns.id, "agent-b"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userGroupRepo.addAgents(group.id, listOf(agentA.id, agentB.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, user.id, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("agent-a", "agent-b")
        }

        "findAvailableByNamespaceIdAndUserId with null agentName returns all accessible agents via namespace path" {
            val ns = namespaceRepo.save(namespace())
            val user = userRepo.save(user("alice@example.com"))
            val agentA = agentConfigRepo.save(agentConfig(ns.id, "agent-a"))
            val agentB = agentConfigRepo.save(agentConfig(ns.id, "agent-b"))
            namespaceRepo.deployAgents(ns.id, listOf(agentA.id, agentB.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, user.id, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("agent-a", "agent-b")
        }

        "findAvailableByNamespaceIdAndUserId with null agentName returns union of group and namespace paths" {
            val ns = namespaceRepo.save(namespace())
            val user = userRepo.save(user("alice@example.com"))
            val groupAgent = agentConfigRepo.save(agentConfig(ns.id, "group-agent"))
            val nsAgent = agentConfigRepo.save(agentConfig(ns.id, "ns-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userGroupRepo.addAgents(group.id, listOf(groupAgent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(nsAgent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, user.id, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("group-agent", "ns-agent")
        }

        // -------------------------------------------------------------------------
        // findAvailableByNamespaceIdAndUserId — agentName filter (exact, case-insensitive)
        // -------------------------------------------------------------------------

        "findAvailableByNamespaceIdAndUserId with agentName returns only the matching agent" {
            val ns = namespaceRepo.save(namespace())
            val user = userRepo.save(user("alice@example.com"))
            val agentA = agentConfigRepo.save(agentConfig(ns.id, "my-agent"))
            val agentB = agentConfigRepo.save(agentConfig(ns.id, "other-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userGroupRepo.addAgents(group.id, listOf(agentA.id, agentB.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, user.id, "my-agent")

            result shouldHaveSize 1
            result.first().name shouldBe "my-agent"
        }

        "findAvailableByNamespaceIdAndUserId with agentName is case-insensitive" {
            val ns = namespaceRepo.save(namespace())
            val user = userRepo.save(user("alice@example.com"))
            val agent = agentConfigRepo.save(agentConfig(ns.id, "My-Agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, user.id, "MY-AGENT")

            result shouldHaveSize 1
            result.first().name shouldBe "My-Agent"
        }

        "findAvailableByNamespaceIdAndUserId with nonexistent agentName returns empty list" {
            val ns = namespaceRepo.save(namespace())
            val user = userRepo.save(user("alice@example.com"))
            val agent = agentConfigRepo.save(agentConfig(ns.id, "real-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, user.id, "nonexistent")

            result.shouldBeEmpty()
        }

        "findAvailableByNamespaceIdAndUserId with agentName matches across both group and namespace paths" {
            val ns = namespaceRepo.save(namespace())
            val user = userRepo.save(user("alice@example.com"))
            val targetAgent = agentConfigRepo.save(agentConfig(ns.id, "target-agent"))
            val otherAgent = agentConfigRepo.save(agentConfig(ns.id, "other-agent"))
            // target-agent reachable via group; other-agent reachable via namespace
            val group = userGroupRepo.save(userGroup(ns.id))
            userGroupRepo.addAgents(group.id, listOf(targetAgent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(otherAgent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, user.id, "target-agent")

            result shouldHaveSize 1
            result.first().name shouldBe "target-agent"
        }

        // -------------------------------------------------------------------------
        // Spring Data Auditing — @CreatedBy / @LastModifiedBy / @CreatedDate / @LastModifiedDate
        // -------------------------------------------------------------------------

        "save stamps createdBy and modifiedBy from AuditorAware on create" {
            val ns = namespaceRepo.save(namespace())

            val saved = agentConfigRepo.save(agentConfig(ns.id, "audited-agent"))

            saved.metadata.createdBy shouldBe TestAuditConfiguration.TEST_AUDITOR_ID
            saved.metadata.modifiedBy shouldBe TestAuditConfiguration.TEST_AUDITOR_ID
        }

        "save stamps createdDate and lastModifiedDate on create" {
            val ns = namespaceRepo.save(namespace())
            val before = Instant.now()

            val saved = agentConfigRepo.save(agentConfig(ns.id, "timestamped-agent"))

            saved.metadata.created.isAfter(before.minusSeconds(1)) shouldBe true
            saved.metadata.modified.isAfter(before.minusSeconds(1)) shouldBe true
        }

        "save on update preserves createdBy and updates modifiedBy" {
            val ns = namespaceRepo.save(namespace())
            val saved = agentConfigRepo.save(agentConfig(ns.id, "update-audit-agent"))

            val updated = agentConfigRepo.save(saved.copy(name = "renamed-agent"))

            updated.metadata.createdBy shouldBe TestAuditConfiguration.TEST_AUDITOR_ID
            updated.metadata.modifiedBy shouldBe TestAuditConfiguration.TEST_AUDITOR_ID
        }

        "save on update preserves original createdBy when auditor changes" {
            val ns = namespaceRepo.save(namespace())

            // Create with auditor A
            TestAuditConfiguration.currentAuditorId = TestAuditConfiguration.TEST_AUDITOR_ID
            val created = agentConfigRepo.save(agentConfig(ns.id, "multi-auditor-agent"))
            created.metadata.createdBy shouldBe TestAuditConfiguration.TEST_AUDITOR_ID
            created.metadata.modifiedBy shouldBe TestAuditConfiguration.TEST_AUDITOR_ID

            // Update with auditor B
            TestAuditConfiguration.currentAuditorId = TestAuditConfiguration.SECOND_AUDITOR_ID
            val updated = agentConfigRepo.save(created.copy(name = "renamed-by-other-user"))

            // createdBy MUST remain auditor A
            updated.metadata.createdBy shouldBe TestAuditConfiguration.TEST_AUDITOR_ID
            // modifiedBy MUST be auditor B
            updated.metadata.modifiedBy shouldBe TestAuditConfiguration.SECOND_AUDITOR_ID
        }

        "soft delete stamps lastModifiedDate" {
            val ns = namespaceRepo.save(namespace())
            val saved = agentConfigRepo.save(agentConfig(ns.id, "to-delete-agent"))
            val beforeDelete = Instant.now()

            agentConfigRepo.delete(saved.id)

            driver.session().use { session ->
                val record = session.run(
                    "MATCH (a:AgentConfig {id: \$id}) RETURN a.modified as modified, a.removed as removed",
                    mapOf("id" to saved.id.toString()),
                ).single()
                record.get("removed").asBoolean() shouldBe true
                val modified = record.get("modified").asZonedDateTime().toInstant()
                modified.isAfter(beforeDelete.minusSeconds(1)) shouldBe true
            }
        }
    }
}
