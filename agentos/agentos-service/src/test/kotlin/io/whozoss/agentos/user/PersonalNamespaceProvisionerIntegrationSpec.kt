package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.codayAgent.CodayAgentSeeder
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
import java.util.UUID

/**
 * The first-run onboarding on real services and embedded Neo4j: creating a user gives them a personal
 * namespace they administer, with the platform agent `Coday` deployed on it and set as default, and the
 * platform AI models resolve there without any provider of the user's own.
 *
 * The user is created through [UserService.create] like every creation path does, so the whole chain
 * is exercised: the [UserCreatedEvent], its after-commit listener, the permission grant. Both features
 * are off in the test profile and switched on here, which is a context of its own.
 *
 * The database is shared with the other specs of the run (see
 * [io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jHarness]), so every fixture carries a generated
 * suffix and `Coday` is made present or absent by the test that needs it.
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

    private fun newUser(label: String = "user"): User {
        val suffix = UUID.randomUUID()
        return userService.create(
            User(metadata = EntityMetadata(), externalId = "$label-$suffix@example.com", email = "$label-$suffix@example.com", isAdmin = false),
        )
    }

    private fun personalNamespaceOf(user: User) = namespaceService.findByExternalId("user:${user.externalId}")

    private fun platformCoday() = agentConfigService.findByName(null, CodayAgentSeeder.AGENT_NAME)

    init {
        "creating a user gives them a personal namespace they administer, with Coday deployed and as default agent" {
            codayAgentSeeder.seed()
            val coday = platformCoday()!!

            val user = newUser("alice")

            val namespace = personalNamespaceOf(user)!!
            namespace.externalId shouldBe "user:${user.externalId}"
            namespace.name shouldBe user.externalId.substringBefore('@')
            namespace.description shouldBe "Personal space of ${user.externalId}"
            namespace.defaultAgentName shouldBe "Coday"
            namespaceService.findDeployedAgentIds(namespace.id) shouldContain coday.id
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN) shouldContainExactly
                listOf(user.id.toString())
            // The user is a plain member of the platform, not a super-admin: the grant is what gives them the namespace.
            permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), Action.WRITE) shouldBe true
        }

        "a second user gets a distinct namespace that the first cannot administer" {
            codayAgentSeeder.seed()

            val first = newUser("first")
            val second = newUser("second")

            val firstNamespace = personalNamespaceOf(first)!!
            val secondNamespace = personalNamespaceOf(second)!!
            firstNamespace.id shouldNotBe secondNamespace.id
            secondNamespace.externalId shouldBe "user:${second.externalId}"
            permissionService.hasPermission(first.id.toString(), EntityType.NAMESPACE, secondNamespace.id.toString(), Action.READ) shouldBe false
            permissionService.hasPermission(second.id.toString(), EntityType.NAMESPACE, firstNamespace.id.toString(), Action.READ) shouldBe false
        }

        "running the seeders and the provisioner again changes nothing" {
            codayAgentSeeder.seed()
            val user = newUser("stable")
            val before = personalNamespaceOf(user)!!
            val codayBefore = platformCoday()!!
            val deployedBefore = namespaceService.findDeployedAgentIds(before.id)

            codayAgentSeeder.seed()
            provisioner.ensure(user.id, user.externalId) shouldBe true
            provisioner.ensureForAllUsers()

            personalNamespaceOf(user) shouldBe before
            platformCoday() shouldBe codayBefore
            agentConfigService.findByNamespace(null).count { it.name == "Coday" } shouldBe 1
            namespaceService.findDeployedAgentIds(before.id) shouldBe deployedBefore
            permissionService.listUsersWithPermission(EntityType.NAMESPACE, before.id.toString(), PermissionRelation.ADMIN) shouldContainExactly
                listOf(user.id.toString())
            namespaceService.findAll().count { it.externalId == "user:${user.externalId}" } shouldBe 1
        }

        "a user created before Coday exists gets it deployed once the seeder has created it" {
            platformCoday()?.let { agentConfigService.delete(it.id) }
            platformCoday() shouldBe null

            val user = newUser("early")

            val bare = personalNamespaceOf(user)!!
            bare.defaultAgentName shouldBe null
            permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, bare.id.toString(), Action.WRITE) shouldBe true

            codayAgentSeeder.seed()

            val coday = platformCoday()!!
            personalNamespaceOf(user)!!.defaultAgentName shouldBe "Coday"
            namespaceService.findDeployedAgentIds(bare.id) shouldContain coday.id
        }

        "a failed provisioning heals on the next check" {
            codayAgentSeeder.seed()
            val user = newUser("heal")
            val namespace = personalNamespaceOf(user)!!
            // The user lost their grant, as a failed first provisioning would have left them.
            permissionService.revokePermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), Action.WRITE) shouldBe false

            provisioner.ensure(user.id, user.externalId) shouldBe true

            permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), Action.WRITE) shouldBe true
        }

        "the platform BIG alias resolves in a personal namespace through the platform provider, and a user-global one wins" {
            codayAgentSeeder.seed()
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
