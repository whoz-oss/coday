package io.whozoss.agentos.authSetting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.authSetting.ApiKeyAuthSettingDto
import io.whozoss.agentos.authSetting.AuthSetting
import io.whozoss.agentos.authSetting.AuthSettingDto
import io.whozoss.agentos.authSetting.AuthType
import io.whozoss.agentos.authSetting.OAuthDiscoverableAuthSettingDto
import io.whozoss.agentos.authSetting.OAuthMcpDiscoverableAuthSettingDto
import io.whozoss.agentos.authSetting.authSettingFromDataMap
import io.whozoss.agentos.authSetting.toDataMap
import io.whozoss.agentos.auth.OAuthFlowService
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.permissions.ScopedAccessPolicy
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

// Convenience accessor so domain-entity assertions stay map-based.
private val AuthSetting.data: Map<String, String> get() = toDataMap()

// Convenience accessor so DTO assertions stay map-based (maps typed nullable fields).
private fun AuthSettingDto.dataMap(): Map<String, String>? {
    val m = dtoToDataMap(this).filterValues { it.isNotEmpty() }
    return m.ifEmpty { null }
}

/**
 * Unit tests for [AuthSettingController].
 *
 * Covers all three scopes (NS-shared, user × namespace, user-global) plus platform scope,
 * the mass-assignment guard, data map resolution semantics on PUT, and list scope filtering.
 *
 * MVC-layer wiring (Bean Validation, @HideOnAccessDenied → 404, routing) is verified
 * in a separate integration spec.
 */
class AuthSettingControllerSpec :
    StringSpec({

        val service = mockk<AuthSettingService>()
        val namespaceService = mockk<NamespaceService>(relaxed = true)
        val userService = mockk<UserService>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)
        val credentialService = mockk<CredentialService>()
        val oAuthFlowService = mockk<OAuthFlowService>()
        val controller = AuthSettingController(
            service,
            namespaceService,
            userService,
            permissionService,
            ScopedAccessPolicy(permissionService),
            credentialService,
            oAuthFlowService,
        )

        val namespaceId = UUID.randomUUID()
        val aliceId = UUID.randomUUID()
        val bobId = UUID.randomUUID()

        fun aliceUser() =
            User(
                metadata = EntityMetadata(id = aliceId),
                externalId = "alice@example.com",
                email = "alice@example.com",
            )

        fun authFor(userId: UUID) = UsernamePasswordAuthenticationToken(userId.toString(), "n/a", emptyList())

        fun <T> withAuth(
            userId: UUID,
            block: () -> T,
        ): T {
            val previous = SecurityContextHolder.getContext().authentication
            SecurityContextHolder.getContext().authentication = authFor(userId)
            return try {
                block()
            } finally {
                SecurityContextHolder.getContext().authentication = previous
            }
        }

        fun setting(
            id: UUID = UUID.randomUUID(),
            nsId: UUID? = namespaceId,
            uId: UUID? = null,
            name: String = "github-oauth",
            authType: AuthType = AuthType.OAUTH_DISCOVERABLE,
            data: Map<String, String> = emptyMap(),
        ) = authSettingFromDataMap(
            authType = authType,
            data = data,
            metadata = EntityMetadata(id = id),
            namespaceId = nsId,
            userId = uId,
            name = name,
            description = null,
        )

        /**
         * Build a DTO for use as a request body. Uses [OAuthDiscoverableAuthSettingDto] by
         * default (matching the default authType), or [ApiKeyAuthSettingDto] for API_KEY.
         * The optional [data] map is spread into the corresponding typed nullable fields.
         */
        fun resource(
            id: UUID? = UUID.randomUUID(),
            nsId: UUID? = namespaceId,
            uId: UUID? = null,
            name: String = "github-oauth",
            authType: AuthType? = AuthType.OAUTH_DISCOVERABLE,
            data: Map<String, String>? = null,
        ): AuthSettingDto =
            when (authType) {
                AuthType.API_KEY -> {
                    ApiKeyAuthSettingDto(
                        id = id,
                        namespaceId = nsId,
                        userId = uId,
                        name = name,
                        authType = authType,
                        apiKey = data?.get("apiKey"),
                    )
                }

                AuthType.OAUTH_MCP_DISCOVERABLE -> {
                    OAuthMcpDiscoverableAuthSettingDto(
                        id = id,
                        namespaceId = nsId,
                        userId = uId,
                        name = name,
                        authType = authType,
                        resourceUrl = data?.get("resourceUrl"),
                        clientId = data?.get("clientId"),
                        clientSecret = data?.get("clientSecret"),
                        scopes = data?.get("scopes"),
                    )
                }

                else -> {
                    OAuthDiscoverableAuthSettingDto(
                        id = id,
                        namespaceId = nsId,
                        userId = uId,
                        name = name,
                        authType = authType ?: AuthType.OAUTH_DISCOVERABLE,
                        discoveryUrl = data?.get("discoveryUrl"),
                        clientId = data?.get("clientId"),
                        clientSecret = data?.get("clientSecret"),
                        scopes = data?.get("scopes"),
                    )
                }
            }

        val existingNamespace =
            Namespace(
                metadata = EntityMetadata(id = namespaceId),
                externalId = "ns-$namespaceId",
                name = "ns",
            )

        beforeTest {
            clearAllMocks()
            every { namespaceService.findById(namespaceId) } returns existingNamespace
            every { userService.getCurrentUser() } returns aliceUser()
        }

        // -------------------------------------------------------------------------
        // toDto — mapping and masking
        // -------------------------------------------------------------------------

        "toDto masks sensitive values and leaves non-sensitive values plain" {
            val s = setting(data = mapOf("clientSecret" to "sk-ant-api03-abcdefghijklmnop", "clientId" to "my-client-id"))
            val dto = toDto(s)
            // clientSecret is sensitive — must be masked
            (dto as OAuthDiscoverableAuthSettingDto).clientSecret shouldBe "sk-a****mnop"
            // clientId is NOT sensitive — must be plain
            dto.clientId shouldBe "my-client-id"
        }

        "toDto returns null typed fields when data map is empty" {
            val dto = toDto(setting(data = emptyMap())) as OAuthDiscoverableAuthSettingDto
            dto.clientSecret.shouldBeNull()
            dto.clientId.shouldBeNull()
        }

        "toDto maps namespaceId and userId" {
            val uid = UUID.randomUUID()
            val dto = toDto(setting(nsId = namespaceId, uId = uid))
            dto.namespaceId shouldBe namespaceId
            dto.userId shouldBe uid
        }

        "toDto maps all fields and masks only the sensitive apiKey" {
            val s =
                setting(
                    name = "my-oauth",
                    authType = AuthType.API_KEY,
                    data = mapOf("apiKey" to "sk-openai-123456789012"),
                )
            val dto = toDto(s) as ApiKeyAuthSettingDto

            dto.id shouldBe s.metadata.id
            dto.name shouldBe "my-oauth"
            dto.authType shouldBe AuthType.API_KEY
            dto.apiKey shouldBe maskSensitiveValue("sk-openai-123456789012")
            dto.apiKey shouldNotBe "sk-openai-123456789012"
        }

        // -------------------------------------------------------------------------
        // create — mass-assignment guard
        // -------------------------------------------------------------------------

        "create rejects body.userId mismatched with authenticated principal with 400" {
            withAuth(aliceId) {
                shouldThrow<BadRequestException> {
                    controller.create(resource(id = null, nsId = null, uId = bobId))
                }
            }
            verify(exactly = 0) { service.create(any()) }
        }

        // -------------------------------------------------------------------------
        // create — platform scope
        // -------------------------------------------------------------------------

        "create with neither namespaceId nor userId is platform scope: non-admin gets AccessDeniedException" {
            every { userService.getCurrentUser() } returns aliceUser()

            withAuth(aliceId) {
                shouldThrow<org.springframework.security.access.AccessDeniedException> {
                    controller.create(resource(id = null, nsId = null, uId = null))
                }
            }
            verify(exactly = 0) { service.create(any()) }
        }

        "create with neither namespaceId nor userId is platform scope: super-admin succeeds" {
            every { userService.getCurrentUser() } returns aliceUser().copy(isAdmin = true)
            val captured = slot<AuthSetting>()
            every { service.create(capture(captured)) } answers { firstArg() }

            withAuth(aliceId) {
                controller.create(resource(id = null, nsId = null, uId = null, name = "platform-setting"))
            }

            captured.captured.namespaceId shouldBe null
            captured.captured.userId shouldBe null
        }

        // -------------------------------------------------------------------------
        // create — per-scope authz
        // -------------------------------------------------------------------------

        "create NS-shared requires WRITE on namespace and persists with userId=null" {
            every {
                permissionService.hasPermission(
                    aliceId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
            } returns true
            val captured = slot<AuthSetting>()
            every { service.create(capture(captured)) } answers { firstArg() }

            withAuth(aliceId) {
                controller.create(resource(id = null, nsId = namespaceId, uId = null, name = "shared"))
            }

            captured.captured.namespaceId shouldBe namespaceId
            captured.captured.userId shouldBe null
        }

        "create NS-shared without WRITE permission throws AccessDeniedException" {
            every {
                permissionService.hasPermission(
                    aliceId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
            } returns false

            shouldThrow<org.springframework.security.access.AccessDeniedException> {
                withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, uId = null)) }
            }
            verify(exactly = 0) { service.create(any()) }
        }

        "create user-namespace with READ permission succeeds and persists userId=auth.name" {
            every {
                permissionService.hasPermission(
                    aliceId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.READ,
                )
            } returns true
            val captured = slot<AuthSetting>()
            every { service.create(capture(captured)) } answers { firstArg() }

            withAuth(aliceId) {
                controller.create(resource(id = null, nsId = namespaceId, uId = aliceId))
            }

            captured.captured.namespaceId shouldBe namespaceId
            captured.captured.userId shouldBe aliceId
        }

        "create user-namespace without READ permission throws AccessDeniedException" {
            every {
                permissionService.hasPermission(
                    aliceId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.READ,
                )
            } returns false

            shouldThrow<org.springframework.security.access.AccessDeniedException> {
                withAuth(aliceId) { controller.create(resource(id = null, nsId = namespaceId, uId = aliceId)) }
            }
            verify(exactly = 0) { service.create(any()) }
        }

        "create user-global skips namespace permission check and persists userId=auth.name with null namespaceId" {
            val captured = slot<AuthSetting>()
            every { service.create(capture(captured)) } answers { firstArg() }

            withAuth(aliceId) { controller.create(resource(id = null, nsId = null, uId = aliceId, name = "global")) }

            captured.captured.namespaceId shouldBe null
            captured.captured.userId shouldBe aliceId
            verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
        }

        "create duplicate triple propagates 409 from service" {
            every {
                permissionService.hasPermission(
                    aliceId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.READ,
                )
            } returns true
            every { service.create(any()) } throws ResponseStatusException(HttpStatus.CONFLICT, "duplicate")

            val ex =
                withAuth(aliceId) {
                    shouldThrow<ResponseStatusException> {
                        controller.create(resource(id = null, nsId = namespaceId, uId = aliceId))
                    }
                }
            ex.statusCode.value() shouldBe 409
        }

        // -------------------------------------------------------------------------
        // update — immutable field preservation + data map resolution
        // -------------------------------------------------------------------------

        "update preserves authType even when body sends a different value" {
            val existing = setting(authType = AuthType.OAUTH_DISCOVERABLE)
            val captured = slot<AuthSetting>()
            every { service.findById(existing.metadata.id) } returns existing
            every { service.update(capture(captured)) } answers { firstArg() }

            controller.update(
                id = existing.metadata.id,
                resource = resource(id = existing.metadata.id, authType = AuthType.API_KEY),
            )

            captured.captured.authType shouldBe AuthType.OAUTH_DISCOVERABLE
        }

        "update preserves namespaceId and userId from existing entity" {
            val existing = setting(nsId = namespaceId, uId = null)
            val otherNs = UUID.randomUUID()
            val captured = slot<AuthSetting>()
            every { service.findById(existing.metadata.id) } returns existing
            every { service.update(capture(captured)) } answers { firstArg() }

            controller.update(
                id = existing.metadata.id,
                resource = resource(id = existing.metadata.id, nsId = otherNs, uId = bobId),
            )

            captured.captured.namespaceId shouldBe namespaceId
            captured.captured.userId shouldBe null
        }

        "update with masked clientSecret preserves existing value" {
            val existingData = mapOf("clientSecret" to "real-secret-value-12345")
            val existing = setting(data = existingData)
            val captured = slot<AuthSetting>()
            every { service.findById(existing.metadata.id) } returns existing
            every { service.update(capture(captured)) } answers { firstArg() }

            controller.update(
                id = existing.metadata.id,
                resource = resource(id = existing.metadata.id, data = mapOf("clientSecret" to "re-a****345")),
            )

            captured.captured.data["clientSecret"] shouldBe "real-secret-value-12345"
        }

        "update with non-masked non-blank clientSecret replaces it" {
            val existing =
                setting(
                    authType = AuthType.API_KEY,
                    data = mapOf("apiKey" to "old-key-abcdefghijklmnop"),
                )
            val captured = slot<AuthSetting>()
            every { service.findById(existing.metadata.id) } returns existing
            every { service.update(capture(captured)) } answers { firstArg() }

            controller.update(
                id = existing.metadata.id,
                resource =
                    resource(
                        id = existing.metadata.id,
                        authType = AuthType.API_KEY,
                        data = mapOf("apiKey" to "new-key-abcdefghijklmnop"),
                    ),
            )

            captured.captured.data["apiKey"] shouldBe "new-key-abcdefghijklmnop"
        }

        "update with blank apiKey clears the key" {
            val existing =
                setting(
                    authType = AuthType.API_KEY,
                    data = mapOf("apiKey" to "real-key-abcdefghijklmnop"),
                )
            val captured = slot<AuthSetting>()
            every { service.findById(existing.metadata.id) } returns existing
            every { service.update(capture(captured)) } answers { firstArg() }

            controller.update(
                id = existing.metadata.id,
                resource =
                    resource(
                        id = existing.metadata.id,
                        authType = AuthType.API_KEY,
                        data = mapOf("apiKey" to ""),
                    ),
            )

            captured.captured.data.containsKey("apiKey") shouldBe false
        }

        "update with null data map preserves all existing data values" {
            val existingData = mapOf("clientId" to "my-client", "clientSecret" to "real-secret-value-12345")
            val existing = setting(data = existingData)
            val captured = slot<AuthSetting>()
            every { service.findById(existing.metadata.id) } returns existing
            every { service.update(capture(captured)) } answers { firstArg() }

            controller.update(
                id = existing.metadata.id,
                resource = resource(id = existing.metadata.id, data = null),
            )

            captured.captured.data shouldBe existingData
        }

        "update preserves keys absent from incoming map" {
            val existingData =
                mapOf(
                    "clientId" to "my-client",
                    "clientSecret" to "real-secret-value-12345",
                    "discoveryUrl" to "https://auth.example.com",
                )
            val existing = setting(data = existingData)
            val captured = slot<AuthSetting>()
            every { service.findById(existing.metadata.id) } returns existing
            every { service.update(capture(captured)) } answers { firstArg() }

            // Client only sends clientId (masked) — other keys must be preserved
            controller.update(
                id = existing.metadata.id,
                resource = resource(id = existing.metadata.id, data = mapOf("clientId" to "my-c****ent")),
            )

            // clientId preserved (masked), clientSecret and discoveryUrl untouched
            captured.captured.data["clientId"] shouldBe "my-client"
            captured.captured.data["clientSecret"] shouldBe "real-secret-value-12345"
            captured.captured.data["discoveryUrl"] shouldBe "https://auth.example.com"
        }

        "update throws 404 when the AuthSetting does not exist" {
            val id = UUID.randomUUID()
            every { service.findById(id) } returns null

            shouldThrow<ResourceNotFoundException> { controller.update(id, resource(id = id)) }
        }

        // -------------------------------------------------------------------------
        // list — scope filtering and userId sentinel validation
        // -------------------------------------------------------------------------

        "list without filter returns the caller's overlays with masked sensitive data" {
            val rows =
                listOf(
                    setting(
                        nsId = null,
                        uId = aliceId,
                        name = "GLOBAL",
                        authType = AuthType.OAUTH_DISCOVERABLE,
                        data = mapOf("clientSecret" to "real-secret-value-12345"),
                    ),
                    setting(
                        nsId = namespaceId,
                        uId = aliceId,
                        name = "NS",
                        authType = AuthType.API_KEY,
                        data = mapOf("apiKey" to "another-secret-value-12345"),
                    ),
                )
            every { service.findFiltered(any(), any(), any(), any()) } returns rows

            val resp = controller.list(namespaceId = null, userId = null)

            resp.size shouldBe 2
            resp.map { it.name } shouldContainExactlyInAnyOrder listOf("GLOBAL", "NS")
            // sensitive fields must be masked
            val global = resp.first { it.name == "GLOBAL" } as OAuthDiscoverableAuthSettingDto
            global.clientSecret!!.contains("****") shouldBe true
            val ns = resp.first { it.name == "NS" } as ApiKeyAuthSettingDto
            ns.apiKey!!.contains("****") shouldBe true
        }

        "list with namespaceId=none returns only user-global rows" {
            val globalRows = listOf(setting(nsId = null, uId = aliceId, name = "GLOBAL"))
            every { service.findFiltered(any(), any(), any(), any()) } returns globalRows

            val respLower = controller.list(namespaceId = "none", userId = "me")
            respLower.map { it.name } shouldBe listOf("GLOBAL")

            val respUpper = controller.list(namespaceId = "NONE", userId = "me")
            respUpper.map { it.name } shouldBe listOf("GLOBAL")
        }

        "list with specific namespaceId and userId=me returns only that namespace's user rows" {
            val rows = listOf(setting(nsId = namespaceId, uId = aliceId, name = "NS-USER"))
            every { service.findFiltered(any(), any(), any(), any()) } returns rows

            val resp = controller.list(namespaceId = namespaceId.toString(), userId = "me")
            resp.map { it.name } shouldBe listOf("NS-USER")
        }

        "list with specific namespaceId and no userId returns NS-shared rows" {
            val rows =
                listOf(
                    setting(nsId = namespaceId, uId = null, name = "NS-A"),
                    setting(nsId = namespaceId, uId = null, name = "NS-B"),
                )
            every { service.findFiltered(any(), any(), any(), any()) } returns rows

            val resp = controller.list(namespaceId = namespaceId.toString(), userId = null)
            resp.map { it.name } shouldContainExactlyInAnyOrder listOf("NS-A", "NS-B")
        }

        "list NS-shared without READ on the namespace returns empty (no 403)" {
            every { service.findFiltered(any(), any(), any(), any()) } returns emptyList()

            val resp = controller.list(namespaceId = namespaceId.toString(), userId = null)
            resp shouldBe emptyList()
        }

        "list rejects ?userId=<uuid> with 400 (only the 'me' sentinel is exposed)" {
            shouldThrow<BadRequestException> {
                controller.list(namespaceId = null, userId = bobId.toString())
            }
        }

        // -------------------------------------------------------------------------
        // resetMyCredential — personal connection reset
        // -------------------------------------------------------------------------

        "resetMyCredential without a namespace deletes only the caller's credential for that very setting" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns setting(id = settingId, nsId = null, uId = aliceId)
            every { credentialService.delete(aliceId, settingId) } returns true

            controller.resetMyCredential(settingId)

            verify(exactly = 1) { credentialService.delete(aliceId, settingId) }
            verify(exactly = 0) { credentialService.delete(bobId, any()) }
            verify(exactly = 0) { credentialService.deleteByAuthSetting(any()) }
        }

        "resetMyCredential is idempotent when the caller has no credential" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns setting(id = settingId, nsId = null, uId = aliceId)
            every { credentialService.delete(aliceId, settingId) } returns false

            controller.resetMyCredential(settingId)

            verify(exactly = 1) { credentialService.delete(aliceId, settingId) }
        }

        "resetMyCredential with a namespace targets the layer the runtime resolves the name to" {
            // A credential is stored under the lowest layer defining the name, not under the clicked row.
            val clickedId = UUID.randomUUID()
            val baseId = UUID.randomUUID()
            every { service.findById(clickedId) } returns
                setting(id = clickedId, nsId = namespaceId, uId = aliceId, name = "jira")
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns true
            every { service.resolveAuthSetting(namespaceId, aliceId, "jira") } returns
                setting(id = baseId, nsId = null, uId = null, name = "jira")
            every { credentialService.delete(aliceId, baseId) } returns true

            controller.resetMyCredential(clickedId, namespaceId)

            verify(exactly = 1) { credentialService.delete(aliceId, baseId) }
            verify(exactly = 0) { credentialService.delete(aliceId, clickedId) }
        }

        "resetMyCredential refuses a namespace the caller cannot read" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns setting(id = settingId)
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns false

            shouldThrow<org.springframework.security.access.AccessDeniedException> {
                controller.resetMyCredential(settingId, namespaceId)
            }
            verify(exactly = 0) { credentialService.delete(any(), any()) }
        }

        "resetMyCredential answers 404 for an unknown setting" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns null

            shouldThrow<ResourceNotFoundException> { controller.resetMyCredential(settingId) }
            verify(exactly = 0) { credentialService.delete(any(), any()) }
        }

        "resetMyCredential answers 404 when the name does not resolve in that namespace" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns setting(id = settingId, name = "jira")
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns true
            every { service.resolveAuthSetting(namespaceId, aliceId, "jira") } throws
                ConfigNotFoundException(namespaceId, aliceId, "jira")

            shouldThrow<ResourceNotFoundException> { controller.resetMyCredential(settingId, namespaceId) }
            verify(exactly = 0) { credentialService.delete(any(), any()) }
        }

        // -------------------------------------------------------------------------
        // getConnection / connect — direct OAuth connection from the settings page
        // -------------------------------------------------------------------------

        "getConnection reports the usable credential and when it was last written" {
            val settingId = UUID.randomUUID()
            val modified = Instant.parse("2026-01-02T03:04:05Z")
            every { service.findById(settingId) } returns setting(id = settingId, nsId = null, uId = aliceId)
            every { oAuthFlowService.findUsableCredential(aliceId, settingId) } returns
                Credential(
                    metadata = EntityMetadata(modified = modified),
                    userId = aliceId,
                    authSettingId = settingId,
                    credentialType = CredentialType.OAUTH_TOKENS,
                )

            val response = controller.getConnection(settingId)

            response.connected shouldBe true
            response.connectedAt shouldBe modified
        }

        "getConnection reports not connected when nothing usable is stored" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns setting(id = settingId, nsId = null, uId = aliceId)
            every { oAuthFlowService.findUsableCredential(aliceId, settingId) } returns null

            val response = controller.getConnection(settingId)

            response.connected shouldBe false
            response.connectedAt.shouldBeNull()
        }

        "connect starts the authorization of the caller for the resolved setting and returns its URL" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns setting(id = settingId, nsId = null, uId = aliceId)
            every { oAuthFlowService.isInteractiveAvailable() } returns true
            coEvery { oAuthFlowService.startInteractiveConnection(aliceId, any()) } returns "https://provider.example.com/auth?state=s"

            val response = controller.connect(settingId)

            response.authorizationUrl shouldBe "https://provider.example.com/auth?state=s"
            coVerify(exactly = 1) {
                oAuthFlowService.startInteractiveConnection(aliceId, match { it.metadata.id == settingId })
            }
        }

        "connect refuses a setting that is not OAuth" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns
                setting(
                    id = settingId,
                    nsId = null,
                    uId = aliceId,
                    authType = AuthType.API_KEY,
                    data = mapOf("apiKey" to "sk-test-123456789"),
                )

            shouldThrow<BadRequestException> { controller.connect(settingId) }
            coVerify(exactly = 0) { oAuthFlowService.startInteractiveConnection(any(), any()) }
        }

        "connect answers 422 when the instance has no OAuth redirect URI" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns setting(id = settingId, nsId = null, uId = aliceId)
            every { oAuthFlowService.isInteractiveAvailable() } returns false

            shouldThrow<UnprocessableEntityException> { controller.connect(settingId) }
            coVerify(exactly = 0) { oAuthFlowService.startInteractiveConnection(any(), any()) }
        }

        "connect answers 422 when the authorization could not start" {
            val settingId = UUID.randomUUID()
            every { service.findById(settingId) } returns setting(id = settingId, nsId = null, uId = aliceId)
            every { oAuthFlowService.isInteractiveAvailable() } returns true
            coEvery { oAuthFlowService.startInteractiveConnection(aliceId, any()) } returns null

            shouldThrow<UnprocessableEntityException> { controller.connect(settingId) }
        }

        // -------------------------------------------------------------------------
        // OAUTH_MCP_DISCOVERABLE — toDto masking and dtoToDataMap
        // -------------------------------------------------------------------------

        "toDto masks clientSecret for OAUTH_MCP_DISCOVERABLE" {
            val s =
                setting(
                    authType = AuthType.OAUTH_MCP_DISCOVERABLE,
                    data =
                        mapOf(
                            "resourceUrl" to "https://mcp.example.com/sse",
                            "clientId" to "my-client-id",
                            "clientSecret" to "sk-secret-abcdefghijklmnop",
                        ),
                )
            val dto = toDto(s) as OAuthMcpDiscoverableAuthSettingDto

            // clientSecret is sensitive — must be masked
            dto.clientSecret!!.contains("****") shouldBe true
            dto.clientSecret shouldNotBe "sk-secret-abcdefghijklmnop"
            // resourceUrl and clientId are NOT sensitive — must be plain
            dto.resourceUrl shouldBe "https://mcp.example.com/sse"
            dto.clientId shouldBe "my-client-id"
        }

        "dtoToDataMap correctly maps OAuthMcpDiscoverableAuthSettingDto" {
            val dto =
                OAuthMcpDiscoverableAuthSettingDto(
                    namespaceId = namespaceId,
                    name = "mcp-setting",
                    resourceUrl = "https://mcp.example.com",
                    clientId = "my-client",
                    clientSecret = "my-secret",
                    scopes = "read write",
                )
            val map = dtoToDataMap(dto)

            map["resourceUrl"] shouldBe "https://mcp.example.com"
            map["clientId"] shouldBe "my-client"
            map["clientSecret"] shouldBe "my-secret"
            map["scopes"] shouldBe "read write"
        }

        "create works with OAUTH_MCP_DISCOVERABLE" {
            every {
                permissionService.hasPermission(
                    aliceId.toString(),
                    EntityType.NAMESPACE,
                    namespaceId.toString(),
                    Action.WRITE,
                )
            } returns true
            val captured = slot<AuthSetting>()
            every { service.create(capture(captured)) } answers { firstArg() }

            withAuth(aliceId) {
                controller.create(
                    resource(
                        id = null,
                        nsId = namespaceId,
                        uId = null,
                        name = "mcp-setting",
                        authType = AuthType.OAUTH_MCP_DISCOVERABLE,
                        data = mapOf("resourceUrl" to "https://mcp.example.com"),
                    ),
                )
            }

            captured.captured.authType shouldBe AuthType.OAUTH_MCP_DISCOVERABLE
            captured.captured.toDataMap()["resourceUrl"] shouldBe "https://mcp.example.com"
        }

        "update works with OAUTH_MCP_DISCOVERABLE and preserves masked clientSecret" {
            val existing =
                setting(
                    authType = AuthType.OAUTH_MCP_DISCOVERABLE,
                    data =
                        mapOf(
                            "resourceUrl" to "https://mcp.example.com",
                            "clientSecret" to "real-secret-value-12345",
                        ),
                )
            val captured = slot<AuthSetting>()
            every { service.findById(existing.metadata.id) } returns existing
            every { service.update(capture(captured)) } answers { firstArg() }

            controller.update(
                id = existing.metadata.id,
                resource =
                    resource(
                        id = existing.metadata.id,
                        authType = AuthType.OAUTH_MCP_DISCOVERABLE,
                        data =
                            mapOf(
                                "resourceUrl" to "https://mcp.example.com",
                                "clientSecret" to "re-a****345", // masked sentinel
                            ),
                    ),
            )

            captured.captured.toDataMap()["clientSecret"] shouldBe "real-secret-value-12345"
            captured.captured.toDataMap()["resourceUrl"] shouldBe "https://mcp.example.com"
        }
    })
