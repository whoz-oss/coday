package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.config.TestAuditConfiguration
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.userGroup.UserGroup
import io.whozoss.agentos.userGroup.UserGroupRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Persistence contract tests for [PromptRepository.findEffective] Cypher query.
 *
 * Covers the two-case access control logic:
 *
 * Case 1 — Prompts WITH an agentConfigId:
 *   - super-admin (u.isAdmin = true) sees all agent-linked prompts
 *   - user-group path: user is MEMBER|ADMIN of a UserGroup in the namespace
 *                      AND the agent is DEPLOYED_TO that same UserGroup
 *   - no deployment → only super-admin can see; regular users cannot
 *   - namespace membership alone does NOT grant access to agent-linked prompts
 *
 * Case 2 — Prompts WITHOUT an agentConfigId:
 *   - platform/user-global prompts (p.namespaceId IS NULL) are always returned
 *   - super-admin (u.isAdmin = true) sees all namespace-scoped free prompts
 *   - user must be MEMBER|ADMIN of the namespace node to see namespace-scoped free prompts
 *   - no namespace membership → namespace-scoped free prompt is hidden
 */
abstract class AbstractPromptPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var promptRepo: PromptRepository

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

    private fun agentConfig(
        namespaceId: UUID,
        name: String,
        enabled: Boolean = true,
    ) = AgentConfig(metadata = EntityMetadata(), namespaceId = namespaceId, name = name, enabled = enabled)

    private fun user(
        externalId: String,
        isAdmin: Boolean = false,
    ) = User(metadata = EntityMetadata(), externalId = externalId, email = externalId, isAdmin = isAdmin)

    private fun userGroup(
        namespaceId: UUID,
        name: String = "group-${UUID.randomUUID()}",
    ) = UserGroup(metadata = EntityMetadata(), namespaceId = namespaceId, name = name)

    /**
     * Creates a namespace-shared prompt (userId = null) optionally linked to an agent.
     */
    private fun prompt(
        namespaceId: UUID,
        name: String,
        agentConfigId: UUID? = null,
    ) = Prompt(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        userId = null,
        agentConfigId = agentConfigId,
        name = name,
        content = listOf("Hello from $name"),
    )

    /**
     * Creates a platform-level prompt (namespaceId = null, userId = null)
     * optionally linked to an agent.
     */
    private fun platformPrompt(
        name: String,
        agentConfigId: UUID? = null,
    ) = Prompt(
        metadata = EntityMetadata(),
        namespaceId = null,
        userId = null,
        agentConfigId = agentConfigId,
        name = name,
        content = listOf("Platform: $name"),
    )

    // ---------------------------------------------------------------------------
    // Graph helpers
    // ---------------------------------------------------------------------------

    /**
     * Deploys an agent to a UserGroup via a raw DEPLOYED_TO relationship.
     * Mirrors [UserGroupRepository.addAgents] for agents not yet wired through the service layer.
     */
    private fun deployAgentToGroup(
        agentId: UUID,
        groupId: UUID,
    ) = userGroupRepo.addAgents(groupId, listOf(agentId))

    // ---------------------------------------------------------------------------
    // Setup helpers
    // ---------------------------------------------------------------------------

    /**
     * Full group-access setup:
     * - namespace + enabled agent + user-group + user
     * - agent DEPLOYED_TO group, user is MEMBER of group
     *
     * @return Triple(namespace, agent, user)
     */
    private fun setupGroupAccess(
        agentName: String,
        agentEnabled: Boolean = true,
        userEmail: String = "alice@example.com",
    ): Triple<Namespace, AgentConfig, User> {
        val ns = namespaceRepo.save(namespace())
        val agent = agentConfigRepo.save(agentConfig(ns.id, agentName, agentEnabled))
        val group = userGroupRepo.save(userGroup(ns.id))
        val savedUser = userRepo.save(user(userEmail))
        userGroupRepo.addAgents(group.id, listOf(agent.id))
        userGroupRepo.addUsers(group.id, listOf(userEmail))
        return Triple(ns, agent, savedUser)
    }

    init {
        beforeEach {
            Neo4jContainerSupport.clearDatabase(driver)
            TestAuditConfiguration.currentAuditorId = TestAuditConfiguration.TEST_AUDITOR_ID
        }

        // -------------------------------------------------------------------------
        // Prompts without agentConfigId — baseline: platform/user-global always returned;
        // namespace-scoped require namespace membership (tested in Case 2 section below)
        // -------------------------------------------------------------------------

        "platform prompt without agentConfigId is always returned regardless of user-group membership" {
            val ns = namespaceRepo.save(namespace())
            val alice = userRepo.save(user("alice@example.com"))
            promptRepo.save(platformPrompt("platform-free-baseline"))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "platform-free-baseline"
        }

        "multiple platform prompts without agentConfigId are all returned" {
            val ns = namespaceRepo.save(namespace())
            val alice = userRepo.save(user("alice@example.com"))
            promptRepo.save(platformPrompt("platform-a"))
            promptRepo.save(platformPrompt("platform-b"))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("platform-a", "platform-b")
        }

        // -------------------------------------------------------------------------
        // Test case 1: prompt linked to agent deployed to user-group the user belongs to
        // -------------------------------------------------------------------------

        "prompt linked to agent deployed to user-group IS returned when user is a member" {
            val (ns, agent, alice) = setupGroupAccess("group-agent")
            val p = promptRepo.save(prompt(ns.id, "group-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "group-prompt"
        }

        "prompt linked to agent deployed to user-group IS returned when user has ADMIN relation to group" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "admin-group-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            // add as ADMIN instead of MEMBER
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$uid}) MATCH (g:UserGroup {id: \$gid}) MERGE (u)-[:ADMIN]->(g)",
                    mapOf("uid" to alice.id.toString(), "gid" to group.id.toString()),
                )
            }
            promptRepo.save(prompt(ns.id, "admin-group-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "admin-group-prompt"
        }

        // -------------------------------------------------------------------------
        // Test case 2: prompt linked to agent deployed to a group the user does NOT belong to
        // -------------------------------------------------------------------------

        "prompt linked to agent deployed to user-group is NOT returned when user is not a member" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "other-group-agent"))
            val group = userGroupRepo.save(userGroup(ns.id))
            val alice = userRepo.save(user("alice@example.com"))
            val bob = userRepo.save(user("bob@example.com"))
            // deploy agent to group, but only bob is a member — alice is not
            userGroupRepo.addAgents(group.id, listOf(agent.id))
            userGroupRepo.addUsers(group.id, listOf("bob@example.com"))
            promptRepo.save(prompt(ns.id, "bob-only-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Test case 3: prompt linked to agent NOT deployed to any user-group
        // -------------------------------------------------------------------------

        "prompt linked to agent with no DEPLOYED_TO relation is NOT returned for regular user" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "undeployed-agent"))
            val alice = userRepo.save(user("alice@example.com"))
            // no group, no deployment — agent exists but is not deployed anywhere
            promptRepo.save(prompt(ns.id, "undeployed-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result.shouldBeEmpty()
        }

        "prompt linked to agent with no DEPLOYED_TO is not returned even when user has namespace membership" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "undeployed-agent"))
            val alice = userRepo.save(user("alice@example.com"))
            // grant namespace membership directly — but agent has no DEPLOYED_TO relation
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$uid}) MATCH (n:Namespace {id: \$nid}) MERGE (u)-[:MEMBER]->(n)",
                    mapOf("uid" to alice.id.toString(), "nid" to ns.id.toString()),
                )
            }
            promptRepo.save(prompt(ns.id, "namespace-member-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Test case 4: super-admin sees all agent-linked prompts
        // -------------------------------------------------------------------------

        "super-admin sees prompt linked to agent with no DEPLOYED_TO relation" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "undeployed-agent"))
            val admin = userRepo.save(user("admin@example.com", isAdmin = true))
            promptRepo.save(prompt(ns.id, "admin-only-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, admin.id)

            result shouldHaveSize 1
            result.first().name shouldBe "admin-only-prompt"
        }

        "super-admin sees prompt linked to agent deployed to a group they are not a member of" {
            val (ns, agent, _) = setupGroupAccess("group-agent", userEmail = "bob@example.com")
            val admin = userRepo.save(user("admin@example.com", isAdmin = true))
            promptRepo.save(prompt(ns.id, "group-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, admin.id)

            result shouldHaveSize 1
            result.first().name shouldBe "group-prompt"
        }

        "super-admin sees prompts linked to multiple agents across the namespace" {
            val ns = namespaceRepo.save(namespace())
            val agent1 = agentConfigRepo.save(agentConfig(ns.id, "agent-one"))
            val agent2 = agentConfigRepo.save(agentConfig(ns.id, "agent-two"))
            val admin = userRepo.save(user("admin@example.com", isAdmin = true))
            promptRepo.save(prompt(ns.id, "prompt-one", agentConfigId = agent1.id))
            promptRepo.save(prompt(ns.id, "prompt-two", agentConfigId = agent2.id))

            val result = promptRepo.findEffective(ns.id, admin.id)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("prompt-one", "prompt-two")
        }

        // -------------------------------------------------------------------------
        // Test case 5: interaction between Case 1 and Case 2 access control
        // -------------------------------------------------------------------------

        "platform free prompt and agent-linked prompt are both returned when user has group access" {
            val (ns, agent, alice) = setupGroupAccess("deployed-agent")
            // use a platform prompt (namespaceId=null) so it passes Case 2 without namespace membership
            promptRepo.save(platformPrompt("platform-free-prompt"))
            promptRepo.save(prompt(ns.id, "agent-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("platform-free-prompt", "agent-prompt")
        }

        "platform free prompt is returned but agent-linked prompt is not when user has no group access" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "undeployed-agent"))
            val alice = userRepo.save(user("alice@example.com"))
            // use a platform prompt (namespaceId=null) so it passes Case 2 without namespace membership
            promptRepo.save(platformPrompt("platform-free-prompt"))
            promptRepo.save(prompt(ns.id, "restricted-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "platform-free-prompt"
        }

        // -------------------------------------------------------------------------
        // Agent enabled/disabled filtering (pre-existing behaviour, must not regress)
        // -------------------------------------------------------------------------

        "prompt linked to disabled agent is NOT returned even when user has group access" {
            val (ns, agent, alice) = setupGroupAccess("disabled-agent", agentEnabled = false)
            promptRepo.save(prompt(ns.id, "disabled-agent-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result.shouldBeEmpty()
        }

        "prompt linked to deleted agent is NOT returned even when user has group access" {
            val (ns, agent, alice) = setupGroupAccess("to-delete-agent")
            promptRepo.save(prompt(ns.id, "deleted-agent-prompt", agentConfigId = agent.id))
            agentConfigRepo.delete(agent.id)

            val result = promptRepo.findEffective(ns.id, alice.id)

            result.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Cross-namespace isolation
        // -------------------------------------------------------------------------

        "user-group membership in another namespace does not grant access to prompts in queried namespace" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "ns-agent"))
            // alice has group access in a DIFFERENT namespace
            val (otherNs, _, alice) = setupGroupAccess("other-ns-agent", userEmail = "alice@example.com")
            promptRepo.save(prompt(ns.id, "cross-ns-prompt", agentConfigId = agent.id))

            // query against ns where alice has no group with the deployed agent
            val result = promptRepo.findEffective(ns.id, alice.id)

            result.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Case 2: prompts without agentConfigId — namespace membership access control
        // -------------------------------------------------------------------------

        "namespace-scoped prompt without agentConfigId IS returned when user is MEMBER of the namespace" {
            val ns = namespaceRepo.save(namespace())
            val alice = userRepo.save(user("alice@example.com"))
            // grant alice MEMBER on the namespace via a raw graph edge
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$uid}) MATCH (n:Namespace {id: \$nid}) MERGE (u)-[:MEMBER]->(n)",
                    mapOf("uid" to alice.id.toString(), "nid" to ns.id.toString()),
                )
            }
            promptRepo.save(prompt(ns.id, "ns-member-prompt"))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "ns-member-prompt"
        }

        "namespace-scoped prompt without agentConfigId IS returned when user is ADMIN of the namespace" {
            val ns = namespaceRepo.save(namespace())
            val alice = userRepo.save(user("alice@example.com"))
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$uid}) MATCH (n:Namespace {id: \$nid}) MERGE (u)-[:ADMIN]->(n)",
                    mapOf("uid" to alice.id.toString(), "nid" to ns.id.toString()),
                )
            }
            promptRepo.save(prompt(ns.id, "ns-admin-prompt"))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "ns-admin-prompt"
        }

        "namespace-scoped prompt without agentConfigId is NOT returned when user has no namespace membership" {
            val ns = namespaceRepo.save(namespace())
            val alice = userRepo.save(user("alice@example.com"))
            // alice exists in graph but has no MEMBER/ADMIN edge to the namespace
            promptRepo.save(prompt(ns.id, "restricted-free-prompt"))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result.shouldBeEmpty()
        }

        "super-admin sees namespace-scoped prompt without agentConfigId regardless of membership" {
            val ns = namespaceRepo.save(namespace())
            val admin = userRepo.save(user("admin@example.com", isAdmin = true))
            promptRepo.save(prompt(ns.id, "admin-free-prompt"))

            val result = promptRepo.findEffective(ns.id, admin.id)

            result shouldHaveSize 1
            result.first().name shouldBe "admin-free-prompt"
        }

        "platform prompt (namespaceId IS NULL) without agentConfigId is always returned regardless of namespace membership" {
            val ns = namespaceRepo.save(namespace())
            val alice = userRepo.save(user("alice@example.com"))
            // alice has NO membership in ns — but platform prompts bypass the namespace check
            promptRepo.save(platformPrompt("platform-free-prompt"))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "platform-free-prompt"
        }

        "user-global prompt (namespaceId IS NULL, userId set) without agentConfigId is always returned" {
            val ns = namespaceRepo.save(namespace())
            val alice = userRepo.save(user("alice@example.com"))
            // user-global prompt: namespaceId=null, userId=alice — no namespace to check
            promptRepo.save(
                Prompt(
                    metadata = EntityMetadata(),
                    namespaceId = null,
                    userId = alice.id,
                    agentConfigId = null,
                    name = "user-global-free-prompt",
                    content = listOf("Hello user-global"),
                ),
            )

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "user-global-free-prompt"
        }

        "namespace membership grants access to free prompt but not to agent-linked prompt in same namespace" {
            val ns = namespaceRepo.save(namespace())
            val agent = agentConfigRepo.save(agentConfig(ns.id, "undeployed-agent"))
            val alice = userRepo.save(user("alice@example.com"))
            // alice is MEMBER of the namespace but agent is not deployed anywhere
            driver.session().use { session ->
                session.run(
                    "MATCH (u:User {id: \$uid}) MATCH (n:Namespace {id: \$nid}) MERGE (u)-[:MEMBER]->(n)",
                    mapOf("uid" to alice.id.toString(), "nid" to ns.id.toString()),
                )
            }
            promptRepo.save(prompt(ns.id, "free-prompt"))
            promptRepo.save(prompt(ns.id, "agent-prompt", agentConfigId = agent.id))

            val result = promptRepo.findEffective(ns.id, alice.id)

            result shouldHaveSize 1
            result.first().name shouldBe "free-prompt"
        }
    }
}
