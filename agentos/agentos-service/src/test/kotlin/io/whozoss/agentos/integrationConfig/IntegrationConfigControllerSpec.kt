package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.Caller
import io.whozoss.agentos.testutil.yamlExportMapper
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigSource
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for the unified [IntegrationConfigController].
 *
 * Covers HTTP-level concerns only: `toDto` mapping, query-parameter parsing, scope
 * dispatch on `list`, and the six `export` tests.
 *
 * Authorization, scope-enforcement, and mass-assignment rules are exercised at the
 * service level in [IntegrationConfigServiceAuthorizationSpec]. MVC-layer wiring
 * (Bean Validation, Spring AOP, @HideOnAccessDenied) is verified in
 * [IntegrationConfigControllerMvcIntegrationSpec] and [IntegrationConfigCrossUserIsolationSpec].
 */
class IntegrationConfigControllerSpec : StringSpec({

    val service = mockk<IntegrationConfigService>()
    val userService = mockk<UserService>(relaxed = true)
    val controller = IntegrationConfigController(service, userService, yamlExportMapper())

    val namespaceId = UUID.randomUUID()
    val aliceId = UUID.randomUUID()
    val bobId = UUID.randomUUID()
    val params = JsonNodeFactory.instance.objectNode().put("apiUrl", "https://example.com")

    // The controller builds this from userService.getCurrentUser(); Caller.Human is a data class,
    // so MockK matches the stubs below by value.
    val aliceCaller = Caller.Human(aliceId, false)

    fun aliceUser(isAdmin: Boolean = false) = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = isAdmin,
    )

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = userId,
        name = name,
        integrationType = integrationType,
        description = null,
        parameters = params,
    )

    beforeTest {
        clearAllMocks()
        every { userService.getCurrentUser() } returns aliceUser()
    }

    // -------------------------------------------------------------------------
    // toDto — mapping (file-level extension)
    // -------------------------------------------------------------------------

    "toDto maps id, namespaceId, userId, name, integrationType, description, parameters, authSettingName" {
        val cfg = config(name = "SLACK_DEV", integrationType = "SLACK", userId = aliceId)
            .copy(description = "Dev Slack", authSettingName = "my-auth")
        val r = IntegrationConfigDto(
            id = cfg.metadata.id,
            namespaceId = cfg.namespaceId,
            userId = cfg.userId,
            name = cfg.name,
            integrationType = cfg.integrationType,
            description = cfg.description,
            parameters = cfg.parameters,
            authSettingName = cfg.authSettingName,
        )

        r.id shouldBe cfg.metadata.id
        r.namespaceId shouldBe namespaceId
        r.userId shouldBe aliceId
        r.name shouldBe "SLACK_DEV"
        r.integrationType shouldBe "SLACK"
        r.description shouldBe "Dev Slack"
        r.parameters shouldBe params
        r.authSettingName shouldBe "my-auth"
        r.source shouldBe IntegrationConfigSource.NEO4J
    }

    // -------------------------------------------------------------------------
    // list — query-param validation
    // -------------------------------------------------------------------------

    "list rejects ?userId=<uuid> with 400 (only the 'me' sentinel is exposed)" {
        shouldThrow<BadRequestException> {
            controller.list(namespaceId = null, userId = bobId.toString())
        }
    }

    "list with invalid namespaceId throws 400 BAD_REQUEST" {
        shouldThrow<BadRequestException> {
            controller.list(namespaceId = "not-a-uuid-and-not-none", userId = null)
        }
    }

    // -------------------------------------------------------------------------
    // list — scope dispatch (restubbed on findFilteredForCaller)
    // -------------------------------------------------------------------------

    "list without namespace filter and userId=me returns caller's own rows" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL_JIRA"),
            config(nsId = namespaceId, userId = aliceId, name = "NS_JIRA"),
        )
        every { service.findFilteredForCaller(aliceCaller, null, false, true) } returns rows

        val resp = controller.list(namespaceId = null, userId = "me")

        resp.size shouldBe 2
        resp.map { it.name } shouldContainExactlyInAnyOrder listOf("GLOBAL_JIRA", "NS_JIRA")
    }

    "list without any param delegates to the caller overlay filter" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "PERSONAL_JIRA"),
        )
        every { service.findFilteredForCaller(aliceCaller, null, false, false) } returns rows

        val resp = controller.list(namespaceId = null, userId = null)

        resp.map { it.name } shouldBe listOf("PERSONAL_JIRA")
    }

    "list with namespaceId=none and userId=me returns only user-global rows" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
        )
        every { service.findFilteredForCaller(aliceCaller, null, true, true) } returns rows

        val resp = controller.list(namespaceId = "none", userId = "me")

        resp.map { it.name } shouldBe listOf("GLOBAL")
    }

    "list with namespaceId=NONE (uppercase) is also user-global" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
        )
        every { service.findFilteredForCaller(aliceCaller, null, true, true) } returns rows

        val resp = controller.list(namespaceId = "NONE", userId = "me")

        resp.map { it.name } shouldBe listOf("GLOBAL")
    }

    "list with specific namespaceId and userId=me returns only that namespace's user rows" {
        val rows = listOf(
            config(nsId = namespaceId, userId = aliceId, name = "NS"),
        )
        every { service.findFilteredForCaller(aliceCaller, namespaceId, false, true) } returns rows

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = "me",
        )

        resp.map { it.name } shouldBe listOf("NS")
    }

    "list with specific namespaceId and no userId returns NS-shared rows" {
        val rows = listOf(
            config(nsId = namespaceId, userId = null, name = "NS-A"),
            config(nsId = namespaceId, userId = null, name = "NS-B"),
        )
        every { service.findFilteredForCaller(aliceCaller, namespaceId, false, false) } returns rows

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = null,
        )

        resp.map { it.name } shouldContainExactlyInAnyOrder listOf("NS-A", "NS-B")
    }

    "list NS-shared without READ on the namespace returns empty (no 403)" {
        every { service.findFilteredForCaller(aliceCaller, namespaceId, false, false) } returns emptyList()

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = null,
        )

        resp shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // export — portability comment block (stub getByIdForCaller with Action.WRITE)
    // -------------------------------------------------------------------------

    "export prefixes the YAML body with a portability comment block mentioning the token" {
        val exportParams = JsonNodeFactory.instance.objectNode().put("workingDirectory", "/home/alice/repos/myproject")
        val cfg = config(name = "BASH_LOCAL", integrationType = "BASH").let {
            it.copy(parameters = exportParams)
        }
        every { service.getByIdForCaller(aliceCaller, cfg.metadata.id, Action.WRITE) } returns cfg

        val response = controller.export(cfg.metadata.id)
        val body = response.body!!

        body shouldStartWith "# "
        body shouldContain "{{NAMESPACE_CONFIG_PATH}}"
    }

    "export produces a body that re-parses to a valid YAML document with name, integrationType, parameters" {
        val exportParams = JsonNodeFactory.instance.objectNode().put("workingDirectory", "/home/alice/repos/myproject")
        val cfg = config(name = "BASH_LOCAL", integrationType = "BASH").let {
            it.copy(parameters = exportParams)
        }
        every { service.getByIdForCaller(aliceCaller, cfg.metadata.id, Action.WRITE) } returns cfg

        val response = controller.export(cfg.metadata.id)
        val body = response.body!!

        val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
        val tree = yamlMapper.readTree(body)

        tree.get("name").asText() shouldBe "BASH_LOCAL"
        tree.get("integrationType").asText() shouldBe "BASH"
        tree.get("parameters").get("workingDirectory").asText() shouldBe "/home/alice/repos/myproject"
    }

    "export serialises parameters as indented YAML, not as a JSON blob on a single line" {
        val exportParams = JsonNodeFactory.instance.objectNode()
            .put("url", "https://mcp.example.com/mcp")
            .put("timeoutSeconds", 30)
        val cfg = config(name = "MCP_HTTP", integrationType = "MCP_HTTP").let {
            it.copy(parameters = exportParams)
        }
        every { service.getByIdForCaller(aliceCaller, cfg.metadata.id, Action.WRITE) } returns cfg

        val body = controller.export(cfg.metadata.id).body!!

        // Parameters must appear as separate YAML keys, not as a single-line JSON blob.
        body shouldContain "url:"
        body shouldContain "timeoutSeconds:"
        // A JSON blob would look like: parameters: {"url":"...","timeoutSeconds":30}
        body.lines().none { it.trim().startsWith("parameters:") && it.contains("{") } shouldBe true
    }

    "export includes authSettingName when present" {
        val cfg = config(name = "JIRA_PROD", integrationType = "JIRA").copy(authSettingName = "my-oauth")
        every { service.getByIdForCaller(aliceCaller, cfg.metadata.id, Action.WRITE) } returns cfg

        val response = controller.export(cfg.metadata.id)
        val body = response.body!!

        body shouldContain "authSettingName"
        body shouldContain "my-oauth"
    }

    "export omits authSettingName when null" {
        val cfg = config(name = "JIRA_PROD", integrationType = "JIRA").copy(authSettingName = null)
        every { service.getByIdForCaller(aliceCaller, cfg.metadata.id, Action.WRITE) } returns cfg

        val response = controller.export(cfg.metadata.id)
        val body = response.body!!

        (body.contains("authSettingName")) shouldBe false
    }

    "export keeps the existing Content-Disposition header and application/yaml content type" {
        val cfg = config(name = "BASH_LOCAL", integrationType = "BASH")
        every { service.getByIdForCaller(aliceCaller, cfg.metadata.id, Action.WRITE) } returns cfg

        val response = controller.export(cfg.metadata.id)

        response.headers.contentDisposition.toString() shouldContain "bash-local.yaml"
        response.headers.contentType.toString() shouldBe org.springframework.http.MediaType.APPLICATION_YAML_VALUE
    }
})
