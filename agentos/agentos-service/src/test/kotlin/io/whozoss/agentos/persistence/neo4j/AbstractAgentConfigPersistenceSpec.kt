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
 * - [AgentConfigRepository.findByParent] with `withDisabled` filter (enabled/disabled,
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

    // ---------------------------------------------------------------------------
    // Entity builders
    // ---------------------------------------------------------------------------

    private fun namespace(externalId: String = "ext-${UUID.randomUUID()}") =
        Namespace(metadata = EntityMetadata(), name = "ns-$externalId", externalId = externalId)

    private fun agentConfig(namespaceId: UUID, name: String) =
        AgentConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = name)

    private fun user(externalId: String) =
        User(metadata = EntityMetadata(), externalId = externalId, email = externalId)

    private fun userGroup(namespaceId: UUID, name: String = "group-${UUID.randomUUID()}") =
        UserGroup(metadata = EntityMetadata(), namespaceId = namespaceId, name = name)

    // ---------------------------------------------------------------------------
    // Graph helpers
    // ---------------------------------------------------------------------------

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

    private fun removeEnabledProperty(agentId: UUID) =
        driver.session().use { session ->
            session.run(
                "MATCH (a:AgentConfig {id: \$id}) REMOVE a.enabled",
                mapOf("id" to agentId.toString()),
            )
        }

    // ---------------------------------------------------------------------------
    // Setup helpers
    // ---------------------------------------------------------------------------

    /**
     * Saves a namespace, one or more agents, a user group, and a user — wiring the user
     * into the group and deploying the agents onto it.
     *
     * @param agentNames Names of the agents to create
     * @param enabled Whether the agents should be enabled (default: true)
     * @param userEmail Email / externalId of the user (default: alice@example.com)
     */
    private fun setupGroupAccess(
        agentNames: List<String>,
        enabled: Boolean = true,
        userEmail: String = "alice@example.com",
    ): Triple<Namespace, List<AgentConfig>, User> {
        val ns = namespaceRepo.save(namespace())
        val agents = agentNames.map { agentConfigRepo.save(agentConfig(ns.id, it).copy(enabled = enabled)) }
        val group = userGroupRepo.save(userGroup(ns.id))
        val savedUser = userRepo.save(user(userEmail))
        userGroupRepo.addAgents(group.id, agents.map { it.id })
        userGroupRepo.addUsers(group.id, listOf(userEmail))
        return Triple(ns, agents, savedUser)
    }

    /**
     * Saves a namespace, one or more agents, and a user — deploying the agents directly
     * onto the namespace and granting the user a MEMBER relation.
     *
     * @param agentNames Names of the agents to create
     * @param enabled Whether the agents should be enabled (default: true)
     * @param userEmail Email / externalId of the user (default: alice@example.com)
     */
    private fun setupNamespaceAccess(
        agentNames: List<String>,
        enabled: Boolean = true,
        userEmail: String = "alice@example.com",
    ): Triple<Namespace, List<AgentConfig>, User> {
        val ns = namespaceRepo.save(namespace())
        val agents = agentNames.map { agentConfigRepo.save(agentConfig(ns.id, it).copy(enabled = enabled)) }
        val savedUser = userRepo.save(user(userEmail))
        namespaceRepo.deployAgents(ns.id, agents.map { it.id })
        grantMember(userEmail, ns.id.toString())
        return Triple(ns, agents, savedUser)
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

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Path 1: agents deployed on a UserGroup (in the namespace) the user is a member of
        // -------------------------------------------------------------------------

        "returns agents deployed on user group the user is a member of" {
            val (ns, _, alice) = setupGroupAccess(listOf("group-agent"))

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
            val (_, agents, alice) = setupGroupAccess(listOf("other-ns-group-agent"))
            // alice's group and agent belong to a different namespace — query uses ns
            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Path 2: agents deployed directly on the namespace
        // -------------------------------------------------------------------------

        "returns agents deployed on a namespace the user has MEMBER relation on" {
            val (ns, _, alice) = setupNamespaceAccess(listOf("ns-agent"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result shouldHaveSize 1
            result.first().name shouldBe "ns-agent"
        }

        "returns agents deployed on a namespace the user has ADMIN relation on" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "admin-ns-agent").copy(enabled = true))
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

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        "does not return agents deployed on a different namespace" {
            val ns = namespaceRepo.save(namespace())
            val (otherNs, agents, alice) = setupNamespaceAccess(listOf("other-ns-agent"))
            // alice has access in otherNs, but we query ns
            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Union and deduplication
        // -------------------------------------------------------------------------

        "deduplicates agent deployed on two groups the user is a member of" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "shared-agent").copy(enabled = true))
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
            val agent = agentConfigRepo.save(agentConfig(ns.id, "shared-agent").copy(enabled = true))
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
            val groupAgent = agentConfigRepo.save(agentConfig(ns.id, "group-agent").copy(enabled = true))
            val nsAgent = agentConfigRepo.save(agentConfig(ns.id, "ns-agent").copy(enabled = true))
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
            val (ns, agents, alice) = setupGroupAccess(listOf("deleted-agent"))
            agentConfigRepo.delete(agents.first().id)

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findByParent with withDisabled filter
        // -------------------------------------------------------------------------

        "findByParent with withDisabled=true returns all active configs regardless of enabled status" {
            val ns = namespaceRepo.save(namespace())
            val enabled = agentConfigRepo.save(agentConfig(ns.id, "enabled-agent").copy(enabled = true))
            val disabled = agentConfigRepo.save(agentConfig(ns.id, "disabled-agent").copy(enabled = false))

            val result = agentConfigRepo.findByParent(ns.id, withDisabled = true)

            result.map { it.id } shouldContainExactlyInAnyOrder listOf(enabled.id, disabled.id)
        }

        "findByParent with withDisabled=false returns only enabled configs" {
            val ns = namespaceRepo.save(namespace())
            val enabled = agentConfigRepo.save(agentConfig(ns.id, "enabled-agent").copy(enabled = true))
            agentConfigRepo.save(agentConfig(ns.id, "disabled-agent").copy(enabled = false))

            val result = agentConfigRepo.findByParent(ns.id, withDisabled = false)

            result shouldHaveSize 1
            result.first().id shouldBe enabled.id
        }

        "findByParent with withDisabled=false treats null enabled as disabled" {
            val ns = namespaceRepo.save(namespace())
            val saved = agentConfigRepo.save(agentConfig(ns.id, "legacy-agent"))
            removeEnabledProperty(saved.id)

            agentConfigRepo.findByParent(ns.id, withDisabled = false).shouldBeEmpty()
        }

        "findByParent excludes soft-deleted configs" {
            val ns = namespaceRepo.save(namespace())
            val active = agentConfigRepo.save(agentConfig(ns.id, "active-agent"))
            val toDelete = agentConfigRepo.save(agentConfig(ns.id, "deleted-agent"))
            agentConfigRepo.delete(toDelete.id)

            val result = agentConfigRepo.findByParent(ns.id, withDisabled = true)

            result shouldHaveSize 1
            result.first().id shouldBe active.id
        }

        "findByParent with withDisabled=false excludes soft-deleted enabled configs" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "enabled-then-deleted").copy(enabled = true))
            agentConfigRepo.delete(agent.id)

            agentConfigRepo.findByParent(ns.id, withDisabled = false).shouldBeEmpty()
        }

        "findByParent returns configs scoped to the given namespace only" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            val agentInNs1 = agentConfigRepo.save(agentConfig(ns1.id, "ns1-agent"))
            agentConfigRepo.save(agentConfig(ns2.id, "ns2-agent"))

            val result = agentConfigRepo.findByParent(ns1.id, withDisabled = true)

            result shouldHaveSize 1
            result.first().id shouldBe agentInNs1.id
        }

        // -------------------------------------------------------------------------
        // findAvailableByNamespaceIdAndUserId — agentName = null (all accessible)
        // -------------------------------------------------------------------------

        "findAvailableByNamespaceIdAndUserId with null agentName returns all accessible agents via group path" {
            val (ns, _, alice) = setupGroupAccess(listOf("agent-a", "agent-b"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("agent-a", "agent-b")
        }

        "findAvailableByNamespaceIdAndUserId with null agentName returns all accessible agents via namespace path" {
            val (ns, _, alice) = setupNamespaceAccess(listOf("agent-a", "agent-b"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("agent-a", "agent-b")
        }

        "findAvailableByNamespaceIdAndUserId with null agentName returns union of group and namespace paths" {
            val ns = namespaceRepo.save(namespace())
            val groupAgent = agentConfigRepo.save(agentConfig(ns.id, "group-agent").copy(enabled = true))
            val nsAgent = agentConfigRepo.save(agentConfig(ns.id, "ns-agent").copy(enabled = true))
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
        // findAvailableByNamespaceIdAndUserId — agentName filter (exact, case-insensitive)
        // -------------------------------------------------------------------------

        "findAvailableByNamespaceIdAndUserId with agentName returns only the matching agent" {
            val (ns, _, alice) = setupGroupAccess(listOf("my-agent", "other-agent"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, "my-agent")

            result shouldHaveSize 1
            result.first().name shouldBe "my-agent"
        }

        "findAvailableByNamespaceIdAndUserId with agentName is case-insensitive" {
            val (ns, _, alice) = setupGroupAccess(listOf("My-Agent"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, "MY-AGENT")

            result shouldHaveSize 1
            result.first().name shouldBe "My-Agent"
        }

        "findAvailableByNamespaceIdAndUserId with nonexistent agentName returns empty list" {
            val (ns, _, alice) = setupGroupAccess(listOf("real-agent"), enabled = false)

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, "nonexistent").shouldBeEmpty()
        }

        "findAvailableByNamespaceIdAndUserId with agentName matches across both group and namespace paths" {
            val ns = namespaceRepo.save(namespace())
            val targetAgent = agentConfigRepo.save(agentConfig(ns.id, "target-agent").copy(enabled = true))
            val otherAgent = agentConfigRepo.save(agentConfig(ns.id, "other-agent").copy(enabled = true))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(targetAgent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(otherAgent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, "target-agent")

            result shouldHaveSize 1
            result.first().name shouldBe "target-agent"
        }

        // -------------------------------------------------------------------------
        // Path 3: super-admin (user.isAdmin = true)
        // -------------------------------------------------------------------------

        "super-admin sees all enabled agents in the namespace without any deployment" {
            val ns = namespaceRepo.save(namespace())
            val agent1 = agentConfigRepo.save(agentConfig(ns.id, "admin-visible-1").copy(enabled = true))
            val agent2 = agentConfigRepo.save(agentConfig(ns.id, "admin-visible-2").copy(enabled = true))
            val admin = userRepo.save(user("admin@example.com").copy(isAdmin = true))
            // no deployment, no group, no namespace membership

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, admin.id, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("admin-visible-1", "admin-visible-2")
        }

        "super-admin does not see disabled agents" {
            val ns = namespaceRepo.save(namespace())
            agentConfigRepo.save(agentConfig(ns.id, "disabled-for-admin").copy(enabled = false))
            val admin = userRepo.save(user("admin@example.com").copy(isAdmin = true))

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, admin.id, null).shouldBeEmpty()
        }

        "super-admin does not see agents from a different namespace" {
            val ns = namespaceRepo.save(namespace())
            val otherNs = namespaceRepo.save(namespace())
            agentConfigRepo.save(agentConfig(otherNs.id, "other-ns-agent").copy(enabled = true))
            val admin = userRepo.save(user("admin@example.com").copy(isAdmin = true))

            // query against ns — admin has no agents there
            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, admin.id, null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findAvailableByNamespaceIdAndUserId — enabled filtering
        // -------------------------------------------------------------------------

        "findAvailableByNamespaceIdAndUserId excludes disabled agents via group path" {
            val (ns, _, alice) = setupGroupAccess(listOf("disabled-group-agent"), enabled = false)

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        "findAvailableByNamespaceIdAndUserId excludes disabled agents via namespace path" {
            val (ns, _, alice) = setupNamespaceAccess(listOf("disabled-ns-agent"), enabled = false)

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        "findAvailableByNamespaceIdAndUserId treats null enabled as disabled" {
            val (ns, agents, alice) = setupGroupAccess(listOf("legacy-agent"))
            removeEnabledProperty(agents.first().id)

            agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null).shouldBeEmpty()
        }

        "findAvailableByNamespaceIdAndUserId returns only enabled agents from mixed set" {
            val ns = namespaceRepo.save(namespace())
            val enabled = agentConfigRepo.save(agentConfig(ns.id, "enabled-agent").copy(enabled = true))
            val disabled = agentConfigRepo.save(agentConfig(ns.id, "disabled-agent").copy(enabled = false))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(enabled.id, disabled.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result shouldHaveSize 1
            result.first().name shouldBe "enabled-agent"
        }

        "findAvailableByNamespaceIdAndUserId filters by enabled across both group and namespace paths" {
            val ns = namespaceRepo.save(namespace())
            val enabledGroupAgent = agentConfigRepo.save(agentConfig(ns.id, "enabled-group").copy(enabled = true))
            val disabledGroupAgent = agentConfigRepo.save(agentConfig(ns.id, "disabled-group").copy(enabled = false))
            val enabledNsAgent = agentConfigRepo.save(agentConfig(ns.id, "enabled-ns").copy(enabled = true))
            val disabledNsAgent = agentConfigRepo.save(agentConfig(ns.id, "disabled-ns").copy(enabled = false))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(enabledGroupAgent.id, disabledGroupAgent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            namespaceRepo.deployAgents(ns.id, listOf(enabledNsAgent.id, disabledNsAgent.id))
            grantMember("alice@example.com", ns.id.toString())

            val result = agentConfigRepo.findAvailableByNamespaceIdAndUserId(ns.id, alice.id, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("enabled-group", "enabled-ns")
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
