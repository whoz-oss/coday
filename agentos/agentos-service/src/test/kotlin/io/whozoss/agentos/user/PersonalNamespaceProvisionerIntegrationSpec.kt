package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.codayAgent.CodayAgentProperties
import io.whozoss.agentos.codayAgent.CodayAgentSeeder
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The first-run onboarding on real services and embedded Neo4j: creating a user gives them a personal
 * namespace they administer, with its own namespace-scoped agent `Coday` deployed on it and set as default,
 * and the platform AI models resolve there without any provider of the user's own.
 *
 * The user is created through [UserService.create] like every creation path does, so the whole chain
 * is exercised: the [UserCreatedEvent], its after-commit listener, the permission grant. Both features
 * are off in the test profile and switched on here, which is a context of its own.
 *
 * The database is shared with the other specs of the run (see
 * [io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jHarness]), so every fixture carries a generated
 * suffix, and a platform `Coday` (the legacy agent of earlier versions) only exists inside the test that
 * needs one, see [withPlatformCoday].
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "agentos.personal-namespace.enabled=true",
        "agentos.defaults.coday-agent.enabled=true",
    ],
)
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class PersonalNamespaceProvisionerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var userService: UserService

    @Autowired lateinit var namespaceService: NamespaceService

    @Autowired lateinit var permissionService: PermissionService

    @Autowired lateinit var agentConfigService: AgentConfigService

    @Autowired lateinit var codayAgentSeeder: CodayAgentSeeder

    @Autowired lateinit var provisioner: PersonalNamespaceProvisioner

    @Autowired lateinit var aiProviderService: AiProviderService

    @Autowired lateinit var aiModelService: AiModelService

    @Autowired lateinit var properties: PersonalNamespaceProperties

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private fun newUser(label: String = "user"): User {
        val suffix = UUID.randomUUID()
        return userService.create(
            User(metadata = EntityMetadata(), externalId = "$label-$suffix@example.com", email = "$label-$suffix@example.com", isAdmin = false),
        )
    }

    private fun personalNamespaceOf(user: User) = namespaceService.findByExternalId("user:${user.externalId}")

    /** The agents named `Coday` that the namespace itself owns (the platform ones are not included). */
    private fun ownCodays(namespace: Namespace): List<AgentConfig> =
        agentConfigService.findByNamespace(namespace.id).filter { it.name == CodayAgentSeeder.AGENT_NAME }

    private fun platformCodays(): List<AgentConfig> = agentConfigService.findByNamespace(null).filter { it.name == CodayAgentSeeder.AGENT_NAME }

    /**
     * Runs [block] with a platform `Coday` (namespaceId = null) in the database, the legacy agent of earlier
     * versions. An existing one is reused and left alone; one created here is deleted afterwards, so the other
     * specs of the run, which share the database, never see it.
     */
    private fun <T> withPlatformCoday(block: (AgentConfig) -> T): T {
        val existing = platformCodays().firstOrNull()
        val platform =
            existing
                ?: agentConfigService.create(
                    AgentConfig(metadata = EntityMetadata(), namespaceId = null, name = CodayAgentSeeder.AGENT_NAME, modelName = "BIG", enabled = true),
                )
        try {
            return block(platform)
        } finally {
            if (existing == null) agentConfigService.delete(platform.id)
        }
    }

    /** A user whose personal namespace is gone, as at the moment of a first launch before any provisioning ran. */
    private fun newUserWithoutNamespace(label: String): User =
        newUser(label).also { namespaceService.delete(personalNamespaceOf(it)!!.id) shouldBe true }

    /**
     * The user has one namespace that they administer, owning exactly one `Coday` (at least one when
     * [exactlyOneCoday] is false) that is deployed there.
     */
    private fun assertProvisionedOnce(
        user: User,
        exactlyOneCoday: Boolean = true,
    ) {
        val key = "user:${user.externalId}"
        val namespace = personalNamespaceOf(user)!!
        namespaceService.findAll().count { it.externalId == key } shouldBe 1
        permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN) shouldContainExactly
            listOf(user.id.toString())
        val own = ownCodays(namespace)
        if (exactlyOneCoday) {
            own shouldHaveSize 1
            namespaceService.findDeployedAgentIds(namespace.id) shouldBe setOf(own.single().id)
        } else {
            own.isNotEmpty() shouldBe true
            namespaceService.findDeployedAgentIds(namespace.id) shouldContain own.first().id
        }
        own.forEach { it.namespaceId shouldBe namespace.id }
    }

    init {
        "creating a user gives them a personal namespace they administer, with their own Coday deployed and as default agent" {
            val user = newUser("alice")

            val namespace = personalNamespaceOf(user)!!
            namespace.externalId shouldBe "user:${user.externalId}"
            namespace.name shouldBe user.externalId.substringBefore('@')
            namespace.description shouldBe "Personal space of ${user.externalId}"
            namespace.defaultAgentName shouldBe "Coday"
            val coday = ownCodays(namespace).single()
            coday.namespaceId shouldBe namespace.id
            namespaceService.findDeployedAgentIds(namespace.id) shouldContain coday.id
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN) shouldContainExactly
                listOf(user.id.toString())
            // The user is a plain member of the platform, not a super-admin: the grant is what gives them the namespace.
            permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), Action.WRITE) shouldBe true
        }

        "a second user gets a distinct namespace that the first cannot administer" {
            val first = newUser("first")
            val second = newUser("second")

            val firstNamespace = personalNamespaceOf(first)!!
            val secondNamespace = personalNamespaceOf(second)!!
            firstNamespace.id shouldNotBe secondNamespace.id
            secondNamespace.externalId shouldBe "user:${second.externalId}"
            permissionService.hasPermission(first.id.toString(), EntityType.NAMESPACE, secondNamespace.id.toString(), Action.READ) shouldBe false
            permissionService.hasPermission(second.id.toString(), EntityType.NAMESPACE, firstNamespace.id.toString(), Action.READ) shouldBe false
        }

        "two users get two different Coday agents, each owned by its own namespace and none at platform level" {
            val platformBefore = platformCodays().map { it.id }.toSet()

            val first = newUser("owner-a")
            val second = newUser("owner-b")

            val firstNamespace = personalNamespaceOf(first)!!
            val secondNamespace = personalNamespaceOf(second)!!
            val firstCoday = ownCodays(firstNamespace).single()
            val secondCoday = ownCodays(secondNamespace).single()
            firstCoday.id shouldNotBe secondCoday.id
            firstCoday.name shouldBe "Coday"
            secondCoday.name shouldBe "Coday"
            firstCoday.namespaceId shouldBe firstNamespace.id
            secondCoday.namespaceId shouldBe secondNamespace.id
            firstCoday.namespaceId shouldNotBe null
            secondCoday.namespaceId shouldNotBe null
            // Each namespace deploys its own, never the other's.
            namespaceService.findDeployedAgentIds(firstNamespace.id) shouldBe setOf(firstCoday.id)
            namespaceService.findDeployedAgentIds(secondNamespace.id) shouldBe setOf(secondCoday.id)
            // The provisioner created nothing at platform level.
            platformCodays().map { it.id }.toSet() shouldBe platformBefore
            platformCodays().map { it.id } shouldNotContain firstCoday.id
            platformCodays().map { it.id } shouldNotContain secondCoday.id
        }

        "a namespace that already owns its Coday keeps that exact agent and gets no second one" {
            val user = newUser("keeper")
            val namespace = personalNamespaceOf(user)!!
            // The user made it theirs: changed instructions, which no provisioning may rewrite.
            val mine = agentConfigService.update(ownCodays(namespace).single().copy(instructions = "my own instructions"))

            provisioner.ensure(user.id, user.externalId) shouldBe true
            provisioner.ensure(user.id, user.externalId) shouldBe true

            val own = ownCodays(namespace)
            own shouldHaveSize 1
            own.single().id shouldBe mine.id
            own.single().instructions shouldBe "my own instructions"
            namespaceService.findDeployedAgentIds(namespace.id) shouldBe setOf(mine.id)
        }

        "findByName in the personal namespace returns the namespace's own Coday, not the platform one" {
            withPlatformCoday { platform ->
                val user = newUser("lookup")
                val namespace = personalNamespaceOf(user)!!

                val found = agentConfigService.findByName(namespace.id, "Coday")!!

                found.id shouldNotBe platform.id
                found.namespaceId shouldBe namespace.id
                found.id shouldBe ownCodays(namespace).single().id
            }
        }

        "a platform Coday does not stop a namespace from getting its own" {
            withPlatformCoday { platform ->
                val user = newUserWithoutNamespace("regression")

                provisioner.ensure(user.id, user.externalId) shouldBe true

                val namespace = personalNamespaceOf(user)!!
                val own = ownCodays(namespace).single()
                own.id shouldNotBe platform.id
                own.namespaceId shouldBe namespace.id
            }
        }

        "legacy migration: the platform Coday is undeployed from the personal namespace, which owns its Coday, and is not removed" {
            withPlatformCoday { platform ->
                val user = newUser("legacy")
                val namespace = personalNamespaceOf(user)!!
                val own = ownCodays(namespace).single()
                // As on an existing install: the platform Coday is the one deployed on the personal namespace.
                namespaceService.deployAgents(namespace.id, listOf(platform.id))
                namespaceService.findDeployedAgentIds(namespace.id) shouldBe setOf(own.id, platform.id)

                provisioner.ensure(user.id, user.externalId) shouldBe true

                ownCodays(namespace).single().id shouldBe own.id
                namespaceService.findDeployedAgentIds(namespace.id) shouldBe setOf(own.id)
                // The platform agent row is left alone: neither deleted nor modified.
                val stillThere = agentConfigService.findById(platform.id)!!
                stillThere.metadata.removed shouldBe false
                stillThere.namespaceId.shouldBeNull()
                stillThere.name shouldBe platform.name
                stillThere.instructions shouldBe platform.instructions
                stillThere.enabled shouldBe platform.enabled
                platformCodays().map { it.id } shouldContain platform.id
                // A second run changes nothing.
                provisioner.ensure(user.id, user.externalId) shouldBe true
                namespaceService.findDeployedAgentIds(namespace.id) shouldBe setOf(own.id)
            }
        }

        "legacy migration only concerns the personal namespace being provisioned" {
            withPlatformCoday { platform ->
                val migrated = newUser("migrated")
                val untouched = newUser("untouched")
                val migratedNamespace = personalNamespaceOf(migrated)!!
                val untouchedNamespace = personalNamespaceOf(untouched)!!
                namespaceService.deployAgents(migratedNamespace.id, listOf(platform.id))
                namespaceService.deployAgents(untouchedNamespace.id, listOf(platform.id))

                provisioner.ensure(migrated.id, migrated.externalId) shouldBe true

                namespaceService.findDeployedAgentIds(migratedNamespace.id) shouldBe setOf(ownCodays(migratedNamespace).single().id)
                namespaceService.findDeployedAgentIds(untouchedNamespace.id) shouldContain platform.id
            }
        }

        "running the provisioner again changes nothing" {
            val user = newUser("stable")
            val before = personalNamespaceOf(user)!!
            val codayBefore = ownCodays(before).single()
            val deployedBefore = namespaceService.findDeployedAgentIds(before.id)

            provisioner.ensure(user.id, user.externalId) shouldBe true
            provisioner.ensureForAllUsers()

            personalNamespaceOf(user) shouldBe before
            ownCodays(before) shouldBe listOf(codayBefore)
            namespaceService.findDeployedAgentIds(before.id) shouldBe deployedBefore
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, before.id.toString(), PermissionRelation.ADMIN) shouldContainExactly
                listOf(user.id.toString())
            namespaceService.findAll().count { it.externalId == "user:${user.externalId}" } shouldBe 1
        }

        "a user provisioned while the default agent is disabled gets no Coday, and gets it once it is enabled" {
            val user = newUserWithoutNamespace("late")
            // A second provisioner whose seeder is disabled, as an instance with the property switched off.
            val disabled =
                PersonalNamespaceProvisioner(
                    properties,
                    namespaceService,
                    permissionService,
                    agentConfigService,
                    CodayAgentSeeder(CodayAgentProperties(enabled = false), agentConfigService),
                    userService,
                    transactionManager,
                )

            disabled.ensure(user.id, user.externalId) shouldBe true

            val bare = personalNamespaceOf(user)!!
            ownCodays(bare) shouldHaveSize 0
            bare.defaultAgentName.shouldBeNull()
            namespaceService.findDeployedAgentIds(bare.id) shouldBe emptySet()
            permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, bare.id.toString(), Action.WRITE) shouldBe true

            // The property is on again: the next check heals the namespace.
            provisioner.ensure(user.id, user.externalId) shouldBe true

            val healed = personalNamespaceOf(user)!!
            val coday = ownCodays(healed).single()
            coday.namespaceId shouldBe healed.id
            healed.defaultAgentName shouldBe "Coday"
            namespaceService.findDeployedAgentIds(healed.id) shouldBe setOf(coday.id)
        }

        "a failed provisioning heals on the next check" {
            val user = newUser("heal")
            val namespace = personalNamespaceOf(user)!!
            // The user lost their grant, as a failed first provisioning would have left them.
            permissionService.revokePermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), Action.WRITE) shouldBe false

            provisioner.ensure(user.id, user.externalId) shouldBe true

            permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), Action.WRITE) shouldBe true
        }

        "concurrent provisioning of one user, as the first requests of a first launch do, creates one namespace and one Coday" {
            val user = newUserWithoutNamespace("race")
            val threads = 8
            val executor = Executors.newFixedThreadPool(threads)
            try {
                val start = CountDownLatch(1)
                val results =
                    (1..threads).map {
                        executor.submit<Boolean> {
                            start.await()
                            provisioner.ensure(user.id, user.externalId)
                        }
                    }
                start.countDown()

                results.map { it.get(30, TimeUnit.SECONDS) } shouldBe List(threads) { true }
            } finally {
                executor.shutdownNow()
            }

            assertProvisionedOnce(user)
        }

        "two instances racing on the same user: the loser re-reads the winner's namespace instead of failing" {
            val user = newUserWithoutNamespace("instances")
            // Two provisioners share nothing, like two AgentOS processes: the per-user lock cannot help, only the
            // unique externalId arbitrates. Both are made to read "no namespace" before either creates.
            val bothHaveRead = CyclicBarrier(2)
            val racing =
                object : NamespaceService by namespaceService {
                    override fun findByExternalId(externalId: String) =
                        namespaceService.findByExternalId(externalId).also { if (it == null) bothHaveRead.await(10, TimeUnit.SECONDS) }
                }
            val instances =
                List(2) {
                    PersonalNamespaceProvisioner(properties, racing, permissionService, agentConfigService, codayAgentSeeder, userService, transactionManager)
                }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = instances.map { instance -> executor.submit<Boolean> { instance.ensure(user.id, user.externalId) } }

                // ensure() returns false when it had to log a failure: both true means the loser recovered.
                results.map { it.get(30, TimeUnit.SECONDS) } shouldBe listOf(true, true)
            } finally {
                executor.shutdownNow()
            }

            // Exactly one Coday is not asserted here: across processes agent names have no uniqueness arbiter (see the
            // KDoc of CodayAgentSeeder), and the two instances here do provision the same brand-new namespace at the
            // same moment, the very case it documents. The single-process tests above pin the exactly-one rule.
            assertProvisionedOnce(user, exactlyOneCoday = false)
        }

        "the platform BIG alias resolves in a personal namespace through the platform provider, and a user-global one wins" {
            val tag = UUID.randomUUID().toString().take(8)
            val alias = "BIG-$tag"
            val provider =
                aiProviderService.create(
                    AiProvider(metadata = EntityMetadata(), name = "platform-$tag", apiType = AiApiType.Anthropic, apiKey = "platform-key"),
                )
            val model = aiModelService.create(AiModel(metadata = EntityMetadata(), aiProviderId = provider.id, apiModelName = "claude-$tag", alias = alias))
            val user = newUser("model")
            val namespace = personalNamespaceOf(user)!!

            // The user has no provider of their own: the alias falls back to the platform layer.
            aiModelService.findAiModel(namespace.id, alias)!!.id shouldBe model.id
            aiModelService.findAiModel(namespace.id, alias.lowercase())!!.id shouldBe model.id
            aiProviderService.resolveProvider(namespace.id, user.id, provider.name).apiKey shouldBe "platform-key"

            // The same provider name in the user's own scope overrides the platform one, for that user only.
            aiProviderService.create(
                AiProvider(metadata = EntityMetadata(), userId = user.id, name = provider.name, apiType = AiApiType.Anthropic, apiKey = "user-key"),
            )
            aiProviderService.resolveProvider(namespace.id, user.id, provider.name).apiKey shouldBe "user-key"
            val other = newUser("other")
            aiProviderService.resolveProvider(personalNamespaceOf(other)!!.id, other.id, provider.name).apiKey shouldBe "platform-key"
            agentConfigService.findByNamespace(namespace.id).map { it.name } shouldNotContain alias
        }
    }
}
