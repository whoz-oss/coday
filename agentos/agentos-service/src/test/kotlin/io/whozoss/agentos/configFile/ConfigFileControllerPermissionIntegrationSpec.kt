package io.whozoss.agentos.configFile

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * [ConfigFileController] through the real permission chain (embedded Neo4j): the list is open to the
 * namespace's members, a diff requires WRITE on the config, like its YAML export.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class ConfigFileControllerPermissionIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var namespaceService: NamespaceService

    @Autowired lateinit var agentConfigService: AgentConfigService

    @Autowired lateinit var integrationConfigService: IntegrationConfigService

    @Autowired lateinit var permissionService: PermissionService

    @Autowired lateinit var userRepository: UserRepository

    @MockkBean(relaxed = true) lateinit var userService: UserService

    private lateinit var alice: User
    private lateinit var namespace: Namespace
    private lateinit var agent: AgentConfig
    private lateinit var integration: IntegrationConfig

    private fun user(isAdmin: Boolean = false): User {
        val id = UUID.randomUUID()
        return userRepository.save(
            User(metadata = EntityMetadata(id = id), externalId = "u-$id@example.com", email = "u-$id@example.com", isAdmin = isAdmin),
        )
    }

    private fun grant(relation: PermissionRelation) =
        permissionService.grantPermission(alice.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), relation)

    init {
        beforeEach {
            alice = user()
            every { userService.getCurrentUser() } returns alice
            every { userService.findById(alice.id) } returns alice

            // The copies are created before the namespace points at its files: with the files visible,
            // the uniqueness checks would refuse them.
            namespace =
                namespaceService.create(
                    Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "ns-${UUID.randomUUID()}", name = "ns"),
                )
            agent =
                agentConfigService.create(
                    AgentConfig(namespaceId = namespace.id, name = "coday", modelName = "BIG", instructions = "Answer in French."),
                )
            integration =
                integrationConfigService.create(
                    IntegrationConfig(
                        namespaceId = namespace.id,
                        name = "repo",
                        integrationType = "FILE_ACCESS",
                        parameters = JsonNodeFactory.instance.objectNode().put("rootPath", "/srv/code"),
                    ),
                )
            val root = Files.createTempDirectory("config-file-permissions")
            root.resolve("agents").createDirectories().resolve("coday.yaml").writeText("name: coday\nmodelName: BIG\n")
            root
                .resolve("integrations")
                .createDirectories()
                .resolve("repo.yaml")
                .writeText("name: repo\nintegrationType: FILE_ACCESS\nparameters:\n  rootPath: /srv/code\n")
            namespace = namespaceService.update(namespace.copy(configPath = root.toString()))
        }

        "a namespace member lists its config files and sees the copies that mask them" {
            grant(PermissionRelation.MEMBER)

            mockMvc
                .perform(get("/api/namespaces/${namespace.id}/config-files"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("AGENT"))
                .andExpect(jsonPath("$[0].path").value("agents/coday.yaml"))
                .andExpect(jsonPath("$[0].state").value("OVERRIDDEN"))
                .andExpect(jsonPath("$[0].configId").value(agent.id.toString()))
                .andExpect(jsonPath("$[0].modified").value(true))
                .andExpect(jsonPath("$[1].kind").value("INTEGRATION"))
                .andExpect(jsonPath("$[1].modified").value(false))
        }

        "a user outside the namespace cannot list its config files" {
            mockMvc.perform(get("/api/namespaces/${namespace.id}/config-files")).andExpect(status().isForbidden)
        }

        "a namespace admin gets the diff of an agent and of an integration" {
            grant(PermissionRelation.ADMIN)

            mockMvc
                .perform(get("/api/agent-configs/${agent.id}/file-diff"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.path").value("agents/coday.yaml"))
                .andExpect(jsonPath("$.modified").value(true))
            mockMvc
                .perform(get("/api/integration-configs/${integration.id}/file-diff"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.modified").value(false))
        }

        "a namespace member cannot get a diff, which shows the config like its export" {
            grant(PermissionRelation.MEMBER)

            mockMvc.perform(get("/api/agent-configs/${agent.id}/file-diff")).andExpect(status().isNotFound)
            mockMvc.perform(get("/api/integration-configs/${integration.id}/file-diff")).andExpect(status().isNotFound)
        }
    }
}
