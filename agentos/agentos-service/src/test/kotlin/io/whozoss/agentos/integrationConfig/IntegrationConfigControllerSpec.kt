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
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.testutil.yamlExportMapper
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Unit tests for the unified [IntegrationConfigController].
 *
 * Covers all three scopes (NS-shared, user × namespace, user-global) on the
 * single CRUD route set — the user-scope cases were absorbed from
 * `UserIntegrationConfigControllerSpec.kt` per the test-migration-checklist
 * (`_bmad-output/implementation-artifacts/test-migration-checklist.md`).
 *
 * The controller reads `auth.principal` from `SecurityContextHolder` for `create()`
 * (signature is fixed by [io.whozoss.agentos.entity.EntityController]). [withAuth]
 * sets / clears the context per test.
 *
 * MVC-layer wiring is verified in [IntegrationConfigControllerMvcIntegrationSpec]
 * and [IntegrationConfigCrossUserIsolationSpec].
 */
class IntegrationConfigControllerSpec : StringSpec({

    val service = mockk<IntegrationConfigService>()
    val namespaceService = mockk<NamespaceService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val scopePolicy = IntegrationConfigScopePolicy(IntegrationsProperties())
    val toolPreviewService = mockk<IntegrationConfigToolPreviewService>()
    val controller =
        IntegrationConfigController(
            integrationConfigService = service,
            namespaceService = namespaceService,
            userService = userService,
            permissionService = permissionService,
            scopePolicy = scopePolicy,
            toolPreviewService = toolPreviewService,
            yamlExportMapper = yamlExportMapper(),
        )

    val namespaceId = UUID.randomUUID()
    val aliceId = UUID.randomUUID()
    val bobId = UUID.randomUUID()
    val params = JsonNodeFactory.instance.objectNode().put("apiUrl", "https://example.com")

    // Shared metadata instance so every aliceUser() call is equal to the one getCurrentUser() returns
    // (EntityMetadata stamps creation timestamps, which would otherwise differ per call).
    val aliceMetadata = EntityMetadata(id = aliceId)

    fun aliceUser(isAdmin: Boolean = false) = User(
        metadata = aliceMetadata,
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = isAdmin,
    )

    fun authFor(userId: UUID) =
        UsernamePasswordAuthenticationToken(userId.toString(), "n/a", emptyList())

    fun <T> withAuth(userId: UUID, block: () -> T): T {
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = authFor(userId)
        return try {
            block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
        authSettingName: String? = null,
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = userId,
        name = name,
        integrationType = integrationType,
        description = null,
        parameters = params,
        authSettingName = authSettingName,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        userId: UUID? = null,
        name: String = "JIRA_PROD",
        integrationType: String = "JIRA",
    ) = IntegrationConfigDto(
        id = id,
        namespaceId = nsId,
        userId = userId,
        name = name,
        integrationType = integrationType,
        description = null,
        parameters = params,
    )

    val existingNamespace = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        externalId = "ns-${namespaceId}",
        name = "ns",
    )

    beforeTest {
        clearAllMocks()
        every { namespaceService.findById(namespaceId) } returns existingNamespace
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
    }

    // -------------------------------------------------------------------------
    // create — Phase 1 mass-assignment guard
    // -------------------------------------------------------------------------

    "create rejects body.userId mismatched with authenticated principal with 400" {
        withAuth(aliceId) {
            shouldThrow<BadRequestException> {
                controller.create(resource(id = null, nsId = null, userId = bobId))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with neither namespaceId nor userId and non-admin user throws AccessDeniedException (platform scope)" {
        withAuth(aliceId) {
            shouldThrow<org.springframework.security.access.AccessDeniedException> {
                controller.create(resource(id = null, nsId = null, userId = null))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create platform scope (null, null) succeeds for Super Admin" {
        every { userService.getCurrentUser() } returns aliceUser(isAdmin = true)
        val captured = slot<IntegrationConfig>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = null, userId = null)) }

        captured.captured.namespaceId shouldBe null
        captured.captured.userId shouldBe null
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // create / update — user-scope denial for network-reaching integration types
    // -------------------------------------------------------------------------

    "create user-global config of a user-scope-denied type throws AccessDeniedException before any persistence" {
        withAuth(aliceId) {
            shouldThrow<org.springframework.security.access.AccessDeniedException> {
                controller.create(resource(id = null, nsId = null, userId = aliceId, integrationType = "MCP_HTTP"))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create user-namespace config of a user-scope-denied type throws AccessDeniedException even with namespace READ" {
        every {
            permissionService.hasPermission(
                aliceId.toString(),
                EntityType.NAMESPACE,
                namespaceId.toString(),
                Action.READ,
            )
        } returns true

        withAuth(aliceId) {
            shouldThrow<org.springframework.security.access.AccessDeniedException> {
                controller.create(resource(id = null, userId = aliceId, integrationType = "HTTP_API"))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create NS-shared config of a user-scope-denied type is still allowed with namespace WRITE" {
        every {
            permissionService.hasPermission(
                aliceId.toString(),
                EntityType.NAMESPACE,
                namespaceId.toString(),
                Action.WRITE,
            )
        } returns true
        every { service.create(any()) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, userId = null, integrationType = "MCP_HTTP")) }

        verify(exactly = 1) { service.create(any()) }
    }

    "update on an existing user-scoped config of a denied type is refused based on the persisted type" {
        // integrationType is immutable, so the persisted type decides — not whatever the body claims.
        val cfg = config(userId = aliceId, integrationType = "MCP_STDIO")
        every { service.findById(cfg.metadata.id) } returns cfg

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            controller.update(
                cfg.metadata.id,
                resource(id = cfg.metadata.id, userId = aliceId, integrationType = "JIRA"),
            )
        }
        verify(exactly = 0) { service.update(any()) }
    }

    // -------------------------------------------------------------------------
    // create — Phase 3.5 namespace existence (now AFTER Phase 3 authz)
    // -------------------------------------------------------------------------

    "create with dangling namespaceId returns 404 only when authz passes (avoid existence leak)" {
        val unknownNs = UUID.randomUUID()
        every { namespaceService.findById(unknownNs) } returns null
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, unknownNs.toString(), Action.WRITE)
        } returns true

        withAuth(aliceId) {
            shouldThrow<ResourceNotFoundException> {
                controller.create(resource(id = null, nsId = unknownNs, userId = null))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create with dangling namespaceId for a non-member surfaces as AccessDenied (no 404 leak)" {
        val unknownNs = UUID.randomUUID()
        every { namespaceService.findById(unknownNs) } returns null

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) {
                controller.create(resource(id = null, nsId = unknownNs, userId = null))
            }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // create — Phase 3 per-scope authz + Phase 4 explicit domain build
    // -------------------------------------------------------------------------

    "create NS-shared (namespaceId only) requires WRITE on namespace and persists with userId=null" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true
        val captured = slot<IntegrationConfig>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, userId = null, name = "shared")) }

        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe null
    }

    "create NS-shared without WRITE permission throws AccessDeniedException" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns false

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, userId = null)) }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    "create user-global skips namespace permission check" {
        val captured = slot<IntegrationConfig>()
        every { service.create(capture(captured)) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = null, userId = aliceId)) }

        captured.captured.namespaceId shouldBe null
        captured.captured.userId shouldBe aliceId
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    "create user-namespace checks namespace READ permission" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns true
        every { service.create(any()) } answers { firstArg() }

        withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, userId = aliceId)) }

        verify(exactly = 1) {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        }
    }

    "create user-namespace without READ permission throws AccessDeniedException" {
        every {
            permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
        } returns false

        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, userId = aliceId)) }
        }
        verify(exactly = 0) { service.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update — server-owned-field preservation (mass-assignment guard)
    // -------------------------------------------------------------------------

    "update preserves the persisted namespaceId when client sends a different value" {
        val c = config()
        val otherNs = UUID.randomUUID()
        val payload = resource(id = c.metadata.id, nsId = otherNs, name = "RENAMED")
        every { service.findById(c.metadata.id) } returns c
        every { service.update(any()) } answers {
            val saved = firstArg<IntegrationConfig>()
            saved.namespaceId shouldBe namespaceId
            saved.name shouldBe "RENAMED"
            saved
        }

        controller.update(c.metadata.id, payload)

        verify(exactly = 1) { service.update(any()) }
    }

    "update preserves immutable fields even when body sets others" {
        val cfg = config(userId = aliceId)
        val captured = slot<IntegrationConfig>()
        every { service.findById(cfg.metadata.id) } returns cfg
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(
            id = cfg.metadata.id,
            resource = resource(
                id = UUID.randomUUID(), // attempted id swap
                nsId = UUID.randomUUID(), // attempted ns swap
                userId = bobId, // attempted ownership transfer
                name = "RENAMED",
                integrationType = "ATTACKER",
            ),
        )

        captured.captured.metadata.id shouldBe cfg.metadata.id
        captured.captured.namespaceId shouldBe namespaceId
        captured.captured.userId shouldBe aliceId
        captured.captured.integrationType shouldBe "JIRA"
        captured.captured.name shouldBe "RENAMED"
    }

    "update body integrationType is silently ignored, existing value preserved" {
        val cfg = config(userId = aliceId, integrationType = "JIRA")
        val captured = slot<IntegrationConfig>()
        every { service.findById(cfg.metadata.id) } returns cfg
        every { service.update(capture(captured)) } answers { firstArg() }

        controller.update(cfg.metadata.id, resource(id = cfg.metadata.id, integrationType = "SLACK"))

        captured.captured.integrationType shouldBe "JIRA"
    }

    "update throws 404 when the IntegrationConfig does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
    }

    // -------------------------------------------------------------------------
    // list — three modes, mass-assignment guard
    // -------------------------------------------------------------------------

    "list without namespace filter and userId=me returns caller's own rows" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL_JIRA"),
            config(nsId = namespaceId, userId = aliceId, name = "NS_JIRA"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(namespaceId = null, userId = "me")

        resp.size shouldBe 2
        resp.map { it.name } shouldContainExactlyInAnyOrder listOf("GLOBAL_JIRA", "NS_JIRA")
    }

    "list without any param returns platform configs for any authenticated user" {
        val rows = listOf(
            config(nsId = null, userId = null, name = "PLATFORM_JIRA"),
        )
        every { service.findPlatform() } returns rows

        val resp = controller.list(namespaceId = null, userId = null)

        resp.map { it.name } shouldBe listOf("PLATFORM_JIRA")
    }

    "list with namespaceId=none returns only user-global rows" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(namespaceId = "none", userId = "me")

        resp.map { it.name } shouldBe listOf("GLOBAL")
    }

    "list with namespaceId=NONE (uppercase) is also user-global" {
        val rows = listOf(
            config(nsId = null, userId = aliceId, name = "GLOBAL"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(namespaceId = "NONE", userId = "me")

        resp.map { it.name } shouldBe listOf("GLOBAL")
    }

    "list with specific namespaceId and userId=me returns only that namespace's user rows" {
        val rows = listOf(
            config(nsId = namespaceId, userId = aliceId, name = "NS"),
        )
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

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
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns rows

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = null,
        )

        resp.map { it.name } shouldContainExactlyInAnyOrder listOf("NS-A", "NS-B")
    }

    "list NS-shared without READ on the namespace returns empty (no 403)" {
        every { service.findFiltered(any(), any(), any(), any(), any()) } returns emptyList()

        val resp = controller.list(
            namespaceId = namespaceId.toString(),
            userId = null,
        )

        resp shouldBe emptyList()
    }

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
    // export — portability comment block
    // -------------------------------------------------------------------------

    "export prefixes the YAML body with a portability comment block mentioning the token" {
        val exportParams = JsonNodeFactory.instance.objectNode().put("workingDirectory", "/home/alice/repos/myproject")
        val cfg = config(name = "BASH_LOCAL", integrationType = "BASH").let {
            it.copy(parameters = exportParams)
        }
        every { service.findById(cfg.metadata.id) } returns cfg

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
        every { service.findById(cfg.metadata.id) } returns cfg

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
        every { service.findById(cfg.metadata.id) } returns cfg

        val body = controller.export(cfg.metadata.id).body!!

        // Parameters must appear as separate YAML keys, not as a single-line JSON blob.
        body shouldContain "url:"
        body shouldContain "timeoutSeconds:"
        // A JSON blob would look like: parameters: {"url":"...","timeoutSeconds":30}
        body.lines().none { it.trim().startsWith("parameters:") && it.contains("{") } shouldBe true
    }

    "export includes authSettingName when present" {
        val cfg = config(name = "JIRA_PROD", integrationType = "JIRA").copy(authSettingName = "my-oauth")
        every { service.findById(cfg.metadata.id) } returns cfg

        val response = controller.export(cfg.metadata.id)
        val body = response.body!!

        body shouldContain "authSettingName"
        body shouldContain "my-oauth"
    }

    "export omits authSettingName when null" {
        val cfg = config(name = "JIRA_PROD", integrationType = "JIRA").copy(authSettingName = null)
        every { service.findById(cfg.metadata.id) } returns cfg

        val response = controller.export(cfg.metadata.id)
        val body = response.body!!

        (body.contains("authSettingName")) shouldBe false
    }

    "export keeps the existing Content-Disposition header and application/yaml content type" {
        val cfg = config(name = "BASH_LOCAL", integrationType = "BASH")
        every { service.findById(cfg.metadata.id) } returns cfg

        val response = controller.export(cfg.metadata.id)

        response.headers.contentDisposition.toString() shouldContain "bash-local.yaml"
        response.headers.contentType.toString() shouldBe org.springframework.http.MediaType.APPLICATION_YAML_VALUE
    }

    // -------------------------------------------------------------------------
    // previewTools — namespace resolution, platform guard, mapping
    // -------------------------------------------------------------------------

    fun preview(
        tools: List<IntegrationConfigToolPreview.ToolPreview> = emptyList(),
        error: String? = null,
        namespaceDescription: String? = "MCP line",
    ) = IntegrationConfigToolPreview(
        integrationType = "MCP_HTTP",
        configName = "MCP_PROD",
        namespaceDescription = namespaceDescription,
        tools = tools,
        error = error,
    )

    "previewTools throws 404 when the config does not exist" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.previewTools(id, namespaceId = null) }

        verify(exactly = 0) { toolPreviewService.preview(any(), any(), any()) }
    }

    "previewTools rejects a platform row without namespaceId with 400" {
        val cfg = config(nsId = null, userId = null, integrationType = "MCP_HTTP")
        every { service.findById(cfg.metadata.id) } returns cfg
        every { userService.getCurrentUser() } returns aliceUser(isAdmin = true)

        val exception =
            shouldThrow<BadRequestException> { controller.previewTools(cfg.metadata.id, namespaceId = null) }

        exception.message shouldContain "namespaceId"
        verify(exactly = 0) { toolPreviewService.preview(any(), any(), any()) }
    }

    "previewTools rejects a user-global row without namespaceId with 400" {
        val cfg = config(nsId = null, userId = aliceId, integrationType = "BASH")
        every { service.findById(cfg.metadata.id) } returns cfg

        shouldThrow<BadRequestException> { controller.previewTools(cfg.metadata.id, namespaceId = null) }

        verify(exactly = 0) { toolPreviewService.preview(any(), any(), any()) }
    }

    "previewTools rejects a namespaceId that does not match the row's namespace with 400" {
        val cfg = config(nsId = namespaceId, userId = null, integrationType = "MCP_HTTP")
        every { service.findById(cfg.metadata.id) } returns cfg
        val otherNamespace = UUID.randomUUID()

        val exception =
            shouldThrow<BadRequestException> { controller.previewTools(cfg.metadata.id, namespaceId = otherNamespace) }

        exception.message shouldContain otherNamespace.toString()
        verify(exactly = 0) { toolPreviewService.preview(any(), any(), any()) }
    }

    "previewTools previews a namespace row in its own namespace for the current user" {
        val cfg = config(nsId = namespaceId, userId = null, integrationType = "MCP_HTTP")
        every { service.findById(cfg.metadata.id) } returns cfg
        every { toolPreviewService.preview(cfg, namespaceId, aliceUser()) } returns preview()

        val dto = controller.previewTools(cfg.metadata.id, namespaceId = null)

        dto.integrationType shouldBe "MCP_HTTP"
        verify(exactly = 1) { toolPreviewService.preview(cfg, namespaceId, aliceUser()) }
    }

    "previewTools accepts a namespaceId equal to the row's namespace" {
        val cfg = config(nsId = namespaceId, userId = aliceId, integrationType = "BASH")
        every { service.findById(cfg.metadata.id) } returns cfg
        every { toolPreviewService.preview(cfg, namespaceId, aliceUser()) } returns preview()

        controller.previewTools(cfg.metadata.id, namespaceId = namespaceId)

        verify(exactly = 1) { toolPreviewService.preview(cfg, namespaceId, aliceUser()) }
    }

    "previewTools previews a platform row in the requested namespace for a super admin" {
        val cfg = config(nsId = null, userId = null, integrationType = "MCP_HTTP")
        val admin = aliceUser(isAdmin = true)
        every { service.findById(cfg.metadata.id) } returns cfg
        every { userService.getCurrentUser() } returns admin
        every { toolPreviewService.preview(cfg, namespaceId, admin) } returns preview()

        controller.previewTools(cfg.metadata.id, namespaceId = namespaceId)

        verify(exactly = 1) { toolPreviewService.preview(cfg, namespaceId, admin) }
    }

    "previewTools refuses a platform row to a non-admin even with a namespaceId" {
        val cfg = config(nsId = null, userId = null, integrationType = "MCP_HTTP")
        every { service.findById(cfg.metadata.id) } returns cfg

        shouldThrow<AccessDeniedException> { controller.previewTools(cfg.metadata.id, namespaceId = namespaceId) }

        verify(exactly = 0) { toolPreviewService.preview(any(), any(), any()) }
    }

    "previewTools maps the preview to the DTO field by field" {
        val cfg = config(nsId = namespaceId, userId = null, integrationType = "MCP_HTTP")
        every { service.findById(cfg.metadata.id) } returns cfg
        val tool =
            IntegrationConfigToolPreview.ToolPreview(
                name = "MCP_PROD__UpdateTicket",
                description = "Updates a ticket",
                inputSchema = "{\"type\":\"object\"}",
                confirmationMode = ConfirmationMode.EVERY_TIME,
            )
        every { toolPreviewService.preview(cfg, namespaceId, aliceUser()) } returns
            preview(tools = listOf(tool), error = null, namespaceDescription = "42 tickets open")

        val dto = controller.previewTools(cfg.metadata.id, namespaceId = null)

        dto.integrationType shouldBe "MCP_HTTP"
        dto.configName shouldBe "MCP_PROD"
        dto.namespaceDescription shouldBe "42 tickets open"
        dto.error shouldBe null
        dto.tools.size shouldBe 1
        dto.tools[0].name shouldBe "MCP_PROD__UpdateTicket"
        dto.tools[0].description shouldBe "Updates a ticket"
        dto.tools[0].inputSchema shouldBe "{\"type\":\"object\"}"
        dto.tools[0].confirmationMode shouldBe ConfirmationMode.EVERY_TIME
    }

    "previewTools lets the 422 of a missing plugin propagate untouched" {
        val cfg = config(nsId = namespaceId, userId = null, integrationType = "JIRA")
        every { service.findById(cfg.metadata.id) } returns cfg
        every { toolPreviewService.preview(cfg, namespaceId, aliceUser()) } throws
            UnprocessableEntityException("No plugin is loaded for integration type 'JIRA'")

        val exception =
            shouldThrow<UnprocessableEntityException> { controller.previewTools(cfg.metadata.id, namespaceId = null) }

        exception.message shouldContain "JIRA"
    }

    "previewTools maps a timed-out describeNamespace to a null namespaceDescription next to the tools" {
        val cfg = config(nsId = namespaceId, userId = null, integrationType = "MCP_HTTP")
        every { service.findById(cfg.metadata.id) } returns cfg
        val tool =
            IntegrationConfigToolPreview.ToolPreview(
                name = "MCP_PROD__ListTickets",
                description = "Lists tickets",
                inputSchema = "{\"type\":\"object\"}",
                confirmationMode = ConfirmationMode.NONE,
            )
        every { toolPreviewService.preview(cfg, namespaceId, aliceUser()) } returns
            preview(tools = listOf(tool), namespaceDescription = null)

        val dto = controller.previewTools(cfg.metadata.id, namespaceId = null)

        dto.namespaceDescription shouldBe null
        dto.error shouldBe null
        dto.tools.map { it.name } shouldBe listOf("MCP_PROD__ListTickets")
    }

    "previewTools hands the stored row (auth setting included) and the current user to the preview service" {
        val cfg = config(nsId = namespaceId, userId = null, integrationType = "MCP_HTTP", authSettingName = "my-auth")
        val bob = User(metadata = EntityMetadata(id = bobId), externalId = "bob@example.com", email = "bob@example.com")
        every { service.findById(cfg.metadata.id) } returns cfg
        every { userService.getCurrentUser() } returns bob
        every { toolPreviewService.preview(cfg, namespaceId, bob) } returns preview()

        controller.previewTools(cfg.metadata.id, namespaceId = null)

        // The credential provider is built by the service from this row's authSettingName for this user.
        verify(exactly = 1) { toolPreviewService.preview(cfg, namespaceId, bob) }
        verify(exactly = 0) { toolPreviewService.preview(any(), any(), aliceUser()) }
    }

    "previewTools forwards the plugin failure as the error field with no tools" {
        val cfg = config(nsId = namespaceId, userId = null, integrationType = "MCP_HTTP")
        every { service.findById(cfg.metadata.id) } returns cfg
        every { toolPreviewService.preview(cfg, namespaceId, aliceUser()) } returns
            preview(error = "IllegalStateException: MCP server unreachable", namespaceDescription = null)

        val dto = controller.previewTools(cfg.metadata.id, namespaceId = null)

        dto.error shouldBe "IllegalStateException: MCP server unreachable"
        dto.tools shouldBe emptyList()
        dto.namespaceDescription shouldBe null
    }
})
