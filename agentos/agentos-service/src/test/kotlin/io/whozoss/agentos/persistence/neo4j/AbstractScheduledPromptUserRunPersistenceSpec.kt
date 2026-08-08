package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.config.TestAuditConfiguration
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptUserRunRepository
import io.whozoss.agentos.scheduledPrompt.UserRunStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.userGroup.UserGroup
import io.whozoss.agentos.userGroup.UserGroupRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Persistence contract tests for [ScheduledPromptUserRunRepository] Cypher queries.
 *
 * Covers:
 * - [ScheduledPromptUserRunRepository.materialize] — UserGroup deployment graph traversal only,
 *   idempotency, soft-delete filtering, ADMIN relation, multi-group deduplication.
 *   Namespace-level deployment is intentionally excluded — only users in UserGroups
 *   explicitly deployed to the agent are targeted.
 * - [ScheduledPromptUserRunRepository.claimBatch] — PENDING→RUNNING transition,
 *   limit enforcement, lease-based crash recovery, terminal status exclusion
 * - [ScheduledPromptUserRunRepository.markTerminal] — DONE/FAILED transitions
 * - [ScheduledPromptUserRunRepository.findByRunId] — ordered retrieval
 * - [ScheduledPromptUserRunRepository.countByRunIdAndStatus] — multi-status counting
 * - [ScheduledPromptUserRunRepository.hasAnyFailed] — boolean failure check
 */
abstract class AbstractScheduledPromptUserRunPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var userRunRepo: ScheduledPromptUserRunRepository

    @Autowired lateinit var namespaceRepo: NamespaceRepository

    @Autowired lateinit var agentConfigRepo: AgentConfigRepository

    @Autowired lateinit var userGroupRepo: UserGroupRepository

    @Autowired lateinit var userRepo: UserRepository

    @Autowired lateinit var driver: Driver

    // ---------------------------------------------------------------------------
    // Entity builders
    // ---------------------------------------------------------------------------

    private fun namespace() =
        Namespace(metadata = EntityMetadata(), name = "test-ns", externalId = "ext-${UUID.randomUUID()}")

    private fun agentConfig(namespaceId: UUID, name: String = "test-agent") =
        AgentConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = name, enabled = true)

    private fun user(externalId: String) =
        User(metadata = EntityMetadata(), externalId = externalId, email = externalId)

    private fun userGroup(namespaceId: UUID, name: String = "test-group") =
        UserGroup(metadata = EntityMetadata(), namespaceId = namespaceId, name = name)

    // ---------------------------------------------------------------------------
    // Graph setup helpers
    // ---------------------------------------------------------------------------

    /**
     * Creates the minimal deployment graph:
     * AgentConfig -[:DEPLOYED_TO]-> UserGroup -[:BELONGS_TO]-> Namespace
     * Users -[:MEMBER]-> UserGroup
     *
     * Returns (namespace, agentConfig, group, savedUsers).
     */
    private fun setupDeployment(
        userEmails: List<String>,
        agentName: String = "test-agent",
    ): DeploymentFixture {
        val ns = namespaceRepo.save(namespace())
        val agent = agentConfigRepo.save(agentConfig(ns.id, agentName))
        val group = userGroupRepo.save(userGroup(ns.id))
        val savedUsers = userEmails.map { userRepo.save(user(it)) }
        userGroupRepo.addAgents(group.id, listOf(agent.id))
        userGroupRepo.addUsers(group.id, userEmails)
        return DeploymentFixture(ns, agent, group, savedUsers)
    }

    private data class DeploymentFixture(
        val ns: Namespace,
        val agent: AgentConfig,
        val group: UserGroup,
        val users: List<User>,
    )

    init {
        beforeEach {
            Neo4jContainerSupport.clearDatabase(driver)
            TestAuditConfiguration.currentAuditorId = TestAuditConfiguration.TEST_AUDITOR_ID
        }

        // -------------------------------------------------------------------------
        // materialize
        // -------------------------------------------------------------------------

        "materialize creates PENDING UserRuns for each target user in the deployment graph" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com", "bob@example.com"))

            val count = userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)

            count shouldBe 2
            val userRuns = userRunRepo.findByRunId(runId)
            userRuns.size shouldBe 2
            userRuns.all { it.status == UserRunStatus.PENDING } shouldBe true
            userRuns.all { it.runId == runId } shouldBe true
        }

        "materialize is idempotent — second call does not duplicate records" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com"))

            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)

            // MERGE is idempotent on the composite UNIQUE (runId, userId) constraint — no duplicate nodes
            userRunRepo.findByRunId(runId).size shouldBe 1
        }

        "materialize returns 0 when no users are deployed" {
            val runId = UUID.randomUUID()
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id))
            // No DEPLOYED_TO edge, no users

            val count = userRunRepo.materialize(runId, agent.id, ns.id)

            count shouldBe 0
            userRunRepo.findByRunId(runId).shouldBeEmpty()
        }

        "materialize excludes soft-deleted users" {
            val runId = UUID.randomUUID()
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            // Soft-delete the user directly via Cypher
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$id}) SET u.removed = true",
                    mapOf("id" to alice.id.toString()),
                )
            }

            val count = userRunRepo.materialize(runId, agent.id, ns.id)

            count shouldBe 0
            userRunRepo.findByRunId(runId).shouldBeEmpty()
        }

        "materialize excludes soft-deleted user groups" {
            val runId = UUID.randomUUID()
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id))
            val group = userGroupRepo.save(userGroup(ns.id))
            userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("alice@example.com"))
            // Soft-delete the group
            driver.session().use { session ->
                session.run(
                    "MATCH (g:UserGroup {id: \$id}) SET g.removed = true",
                    mapOf("id" to group.id.toString()),
                )
            }

            val count = userRunRepo.materialize(runId, agent.id, ns.id)

            count shouldBe 0
        }

        "materialize excludes soft-deleted agent configs" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com"))
            // Soft-delete the agent
            agentConfigRepo.delete(fixture.agent.id)

            val count = userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)

            count shouldBe 0
        }

        "materialize creates UserRuns for users in multiple groups deployed to the same agent" {
            val runId = UUID.randomUUID()
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id))
            val group1 = userGroupRepo.save(userGroup(ns.id, "group-1"))
            val group2 = userGroupRepo.save(userGroup(ns.id, "group-2"))
            userRepo.save(user("alice@example.com"))
            userRepo.save(user("bob@example.com"))
            userGroupRepo.addAgents(group1.id, listOf(agent.id))
            userGroupRepo.addAgents(group2.id, listOf(agent.id))
            userGroupRepo.addUsers(group1.id, listOf("alice@example.com"))
            userGroupRepo.addUsers(group2.id, listOf("bob@example.com"))

            val count = userRunRepo.materialize(runId, agent.id, ns.id)

            count shouldBe 2
            userRunRepo.findByRunId(runId).size shouldBe 2
        }

        "materialize deduplicates users in multiple groups deployed to the same agent" {
            val runId = UUID.randomUUID()
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id))
            val group1 = userGroupRepo.save(userGroup(ns.id, "group-1"))
            val group2 = userGroupRepo.save(userGroup(ns.id, "group-2"))
            userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group1.id, listOf(agent.id))
            userGroupRepo.addAgents(group2.id, listOf(agent.id))
            // alice is in BOTH groups — should produce only one UserRun
            userGroupRepo.addUsers(group1.id, listOf("alice@example.com"))
            userGroupRepo.addUsers(group2.id, listOf("alice@example.com"))

            val count = userRunRepo.materialize(runId, agent.id, ns.id)

            count shouldBe 1
            userRunRepo.findByRunId(runId).size shouldBe 1
        }

        "materialize does not create UserRuns for users in groups not deployed to the agent" {
            val runId = UUID.randomUUID()
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id))
            val undeployedGroup = userGroupRepo.save(userGroup(ns.id, "undeployed-group"))
            userRepo.save(user("alice@example.com"))
            // alice is in a group, but the group has no DEPLOYED_TO edge to agent
            userGroupRepo.addUsers(undeployedGroup.id, listOf("alice@example.com"))

            val count = userRunRepo.materialize(runId, agent.id, ns.id)

            count shouldBe 0
        }

        // -------------------------------------------------------------------------
        // materialize — super-admin exclusion
        // -------------------------------------------------------------------------

        "materialize excludes super-admins not in any deployed group or namespace" {
            val runId = UUID.randomUUID()
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id))
            val group = userGroupRepo.save(userGroup(ns.id))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            // Create a super-admin with no group/namespace membership
            val admin = userRepo.save(user("admin@example.com"))
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$id}) SET u.isAdmin = true",
                    mapOf("id" to admin.id.toString()),
                )
            }

            val count = userRunRepo.materialize(runId, agent.id, ns.id)

            count shouldBe 0
        }

        "materialize includes super-admin who is also member of a deployed group" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("admin@example.com"))
            // Make the user a super-admin too
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$id}) SET u.isAdmin = true",
                    mapOf("id" to fixture.users.first().id.toString()),
                )
            }

            val count = userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)

            count shouldBe 1
        }

        // -------------------------------------------------------------------------
        // materialize — ADMIN relation to group
        // -------------------------------------------------------------------------

        "materialize handles ADMIN relation to group (not just MEMBER)" {
            val runId = UUID.randomUUID()
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            // Create ADMIN relation directly via Cypher (addUsers creates MEMBER)
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$userId}) MATCH (g:UserGroup {id: \$groupId}) MERGE (u)-[:ADMIN]->(g)",
                    mapOf("userId" to alice.id.toString(), "groupId" to group.id.toString()),
                )
            }

            val count = userRunRepo.materialize(runId, agent.id, ns.id)

            count shouldBe 1
            val userRuns = userRunRepo.findByRunId(runId)
            userRuns.size shouldBe 1
            userRuns.first().userId shouldBe alice.id
        }

        // -------------------------------------------------------------------------
        // claimBatch
        // -------------------------------------------------------------------------

        "claimBatch returns empty list when no UserRuns exist" {
            val claimed = userRunRepo.claimBatch(Duration.ofMinutes(30), 10)
            claimed.shouldBeEmpty()
        }

        "claimBatch transitions PENDING to RUNNING and sets leaseUntil" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)

            val claimed = userRunRepo.claimBatch(Duration.ofMinutes(30), 10)

            claimed.size shouldBe 1
            claimed.first().status shouldBe UserRunStatus.RUNNING
            claimed.first().leaseUntil.shouldNotBeNull()
            claimed.first().runId shouldBe runId
        }

        "claimBatch does not return DONE or FAILED entries" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com", "bob@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)
            val userRuns = userRunRepo.findByRunId(runId)
            val now = Instant.now()
            userRunRepo.markTerminal(userRuns[0].id, UserRunStatus.DONE, now)
            userRunRepo.markTerminal(userRuns[1].id, UserRunStatus.FAILED, now, "error")

            val claimed = userRunRepo.claimBatch(Duration.ofMinutes(30), 10)

            claimed.shouldBeEmpty()
        }

        "claimBatch reclaims a RUNNING entry with expired lease" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)
            // Claim with a very short lease
            userRunRepo.claimBatch(Duration.ofMillis(1), 10)
            Thread.sleep(10)

            // Lease has expired — should be re-claimable
            val reclaimed = userRunRepo.claimBatch(Duration.ofMinutes(30), 10)

            reclaimed.size shouldBe 1
            reclaimed.first().status shouldBe UserRunStatus.RUNNING
        }

        "claimBatch respects limit parameter" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com", "bob@example.com", "carol@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)

            val claimed = userRunRepo.claimBatch(Duration.ofMinutes(30), 2)

            claimed.size shouldBe 2
            claimed.all { it.status == UserRunStatus.RUNNING } shouldBe true
        }

        // -------------------------------------------------------------------------
        // markTerminal
        // -------------------------------------------------------------------------

        "markTerminal to DONE sets finishedAt and clears leaseUntil" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)
            val ur = userRunRepo.findByRunId(runId).first()
            val now = Instant.now()

            val done = userRunRepo.markTerminal(ur.id, UserRunStatus.DONE, now)

            done.status shouldBe UserRunStatus.DONE
            done.finishedAt.shouldNotBeNull()
            done.leaseUntil.shouldBeNull()
            done.error.shouldBeNull()
        }

        "markTerminal to FAILED sets error and finishedAt" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)
            val ur = userRunRepo.findByRunId(runId).first()
            val now = Instant.now()

            val failed = userRunRepo.markTerminal(ur.id, UserRunStatus.FAILED, now, "Case creation failed")

            failed.status shouldBe UserRunStatus.FAILED
            failed.error shouldBe "Case creation failed"
            failed.finishedAt.shouldNotBeNull()
        }

        // -------------------------------------------------------------------------
        // findByRunId
        // -------------------------------------------------------------------------

        "findByRunId returns all UserRuns for a given run" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com", "bob@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)

            val results = userRunRepo.findByRunId(runId)

            results.size shouldBe 2
            results.all { it.runId == runId } shouldBe true
        }

        "findByRunId returns empty list when no UserRuns exist" {
            val results = userRunRepo.findByRunId(UUID.randomUUID())
            results.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // countByRunIdAndStatus
        // -------------------------------------------------------------------------

        "countByRunIdAndStatus counts entries matching the given statuses" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com", "bob@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)
            val userRuns = userRunRepo.findByRunId(runId)
            userRunRepo.markTerminal(userRuns[0].id, UserRunStatus.DONE, Instant.now())

            userRunRepo.countByRunIdAndStatus(runId, UserRunStatus.PENDING) shouldBe 1
            userRunRepo.countByRunIdAndStatus(runId, UserRunStatus.DONE) shouldBe 1
            userRunRepo.countByRunIdAndStatus(runId, UserRunStatus.PENDING, UserRunStatus.DONE) shouldBe 2
            userRunRepo.countByRunIdAndStatus(runId, UserRunStatus.FAILED) shouldBe 0
        }

        // -------------------------------------------------------------------------
        // hasAnyFailed
        // -------------------------------------------------------------------------

        "hasAnyFailed returns true when at least one FAILED entry exists" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)
            val ur = userRunRepo.findByRunId(runId).first()
            userRunRepo.markTerminal(ur.id, UserRunStatus.FAILED, Instant.now(), "error")

            userRunRepo.hasAnyFailed(runId) shouldBe true
        }

        "hasAnyFailed returns false when no FAILED entries exist" {
            val runId = UUID.randomUUID()
            val fixture = setupDeployment(listOf("alice@example.com"))
            userRunRepo.materialize(runId, fixture.agent.id, fixture.ns.id)

            userRunRepo.hasAnyFailed(runId) shouldBe false
        }
    }

    private fun List<Any>.shouldBeEmpty() {
        size shouldBe 0
    }
}
