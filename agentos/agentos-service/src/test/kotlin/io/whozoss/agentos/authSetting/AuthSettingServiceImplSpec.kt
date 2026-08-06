package io.whozoss.agentos.authSetting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

// Convenience accessor for tests that reason about the flat data map.
private val AuthSetting.data: Map<String, String> get() = toDataMap()

/**
 * Unit tests for [AuthSettingServiceImpl].
 *
 * Stubs [AuthSettingRepository] directly with MockK (mirrors [io.whozoss.agentos.credential.CredentialServiceImplSpec])
 * rather than a shared in-memory fake — the repository's own filtering/sorting/scoping
 * behaviour is already proven end-to-end by [io.whozoss.agentos.persistence.neo4j.AbstractAuthSettingPersistenceSpec].
 * This spec exercises only what [AuthSettingServiceImpl] itself adds on top of the repository:
 * the triple pre-check, the cross-layer authType consistency guard, and the [resolveAuthSetting]
 * fold. Simple pass-through methods get one delegation test each.
 */
class AuthSettingServiceImplSpec : StringSpec() {
    // PermissionService and UserService are only used by findFiltered, which is not
    // exercised in this spec (tested via controller + integration tests).
    // Relaxed mocks satisfy the constructor without interfering with the tested methods.
    private fun newService(repository: AuthSettingRepository) =
        AuthSettingServiceImpl(
            repository,
            AuthSettingMergeStrategy(),
            mockk<PermissionService>(relaxed = true),
            mockk<UserService>(relaxed = true),
        )

    /** A repository stub with no pre-existing layer anywhere — every create/update in a test using this passes the guards untouched unless a specific `every` is overridden below. */
    private fun repoWithNoConflicts(): AuthSettingRepository {
        val repo = mockk<AuthSettingRepository>()
        every { repo.findByNamespaceIdAndUserIdAndName(any(), any(), any()) } returns null
        every { repo.findByUserId(any()) } returns emptyList()
        every { repo.save(any()) } answers { firstArg() }
        return repo
    }

    private fun setting(
        namespaceId: UUID? = UUID.randomUUID(),
        userId: UUID? = null,
        name: String = "github",
        authType: AuthType = AuthType.OAUTH_DISCOVERABLE,
        data: Map<String, String> = emptyMap(),
        metadata: EntityMetadata = EntityMetadata(),
    ): AuthSetting =
        authSettingFromDataMap(
            authType = authType,
            data = data,
            metadata = metadata,
            namespaceId = namespaceId,
            userId = userId,
            name = name,
            description = null,
        )

    /** Produce an updated entity with a different data map — replaces the old .copy(data=…) pattern. */
    private fun AuthSetting.withData(newData: Map<String, String>): AuthSetting =
        authSettingFromDataMap(
            authType = authType,
            data = newData,
            metadata = metadata,
            namespaceId = namespaceId,
            userId = userId,
            name = name,
            description = description,
        )

    /** Produce an updated entity with a different name — replaces the old .copy(name=…) pattern. */
    private fun AuthSetting.withName(newName: String): AuthSetting =
        authSettingFromDataMap(
            authType = authType,
            data = toDataMap(),
            metadata = metadata,
            namespaceId = namespaceId,
            userId = userId,
            name = newName,
            description = description,
        )

    init {

        // -------------------------------------------------------------------------
        // create — scope invariant (any combination is accepted by the service)
        // -------------------------------------------------------------------------

        "create succeeds with both namespaceId and userId null (platform scope)" {
            val repo = repoWithNoConflicts()
            val entity = setting(namespaceId = null, userId = null)

            val saved = newService(repo).create(entity)

            saved shouldBe entity
            verify(exactly = 1) { repo.save(entity) }
        }

        "create succeeds with namespaceId only" {
            val repo = repoWithNoConflicts()
            val entity = setting(namespaceId = UUID.randomUUID(), userId = null)

            newService(repo).create(entity) shouldBe entity
        }

        "create succeeds with userId only" {
            val repo = repoWithNoConflicts()
            val entity = setting(namespaceId = null, userId = UUID.randomUUID())

            newService(repo).create(entity) shouldBe entity
        }

        "create succeeds with both namespaceId and userId" {
            val repo = repoWithNoConflicts()
            val entity = setting(namespaceId = UUID.randomUUID(), userId = UUID.randomUUID())

            newService(repo).create(entity) shouldBe entity
        }

        // -------------------------------------------------------------------------
        // create — triple uniqueness pre-check
        // -------------------------------------------------------------------------

        "create throws 409 when (namespaceId, userId, name) already exists" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val existing = setting(namespaceId = nsId, userId = null, name = "github")
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, null, "github") } returns existing

            shouldThrow<ResponseStatusException> {
                newService(repo).create(setting(namespaceId = nsId, userId = null, name = "github"))
            }.statusCode.value() shouldBe 409
        }

        // -------------------------------------------------------------------------
        // update — self-exclusion and rename conflict
        // -------------------------------------------------------------------------

        "update allows renaming to the same name (no false conflict with self)" {
            val repo = repoWithNoConflicts()
            val nsId = UUID.randomUUID()
            val original = setting(namespaceId = nsId, name = "github")
            // The pre-check finds the entity itself — must not be treated as a conflict.
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, null, "github") } returns original

            val updated = newService(repo).update(original.withData(mapOf("clientId" to "new-id")))

            updated.name shouldBe "github"
        }

        "update throws 409 when renaming conflicts with another setting in the same scope" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val other = setting(namespaceId = nsId, name = "gitlab")
            val toUpdate = setting(namespaceId = nsId, name = "github")
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, null, "gitlab") } returns other

            shouldThrow<ResponseStatusException> {
                newService(repo).update(toUpdate.withName("gitlab"))
            }.statusCode.value() shouldBe 409
        }

        // -------------------------------------------------------------------------
        // Cross-layer authType consistency guard
        //
        // The 4-tier reconciliation merges layers param-by-param assuming all layers share
        // the same authType. If they diverge, the merged setting silently switches the auth
        // mechanism at runtime.
        // -------------------------------------------------------------------------

        "create user×ns rejects when NS-shared layer has same name with different authType" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val nsShared = setting(namespaceId = nsId, userId = null, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE)
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, userId, "primary") } returns null
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, null, "primary") } returns nsShared

            shouldThrow<ResponseStatusException> {
                newService(repo).create(setting(namespaceId = nsId, userId = userId, name = "primary", authType = AuthType.API_KEY))
            }.statusCode.value() shouldBe 409
        }

        "create user-global rejects when same-user user×ns layer has same name with different authType" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val userNs = setting(namespaceId = nsId, userId = userId, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE)
            // No existing row at the exact (null, userId, "primary") triple -- the pre-check and
            // the user-global consistency check both query this triple and must find nothing.
            every { repo.findByNamespaceIdAndUserIdAndName(null, userId, "primary") } returns null
            // The conflicting layer is the user×ns row, surfaced via findByUserId.
            every { repo.findByUserId(userId) } returns listOf(userNs)

            shouldThrow<ResponseStatusException> {
                newService(repo).create(setting(namespaceId = null, userId = userId, name = "primary", authType = AuthType.API_KEY))
            }.statusCode.value() shouldBe 409
        }

        "create user×ns rejects when same-user user-global layer has same name with different authType" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val userGlobal = setting(namespaceId = null, userId = userId, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE)
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, userId, "primary") } returns null
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, null, "primary") } returns null
            every { repo.findByNamespaceIdAndUserIdAndName(null, userId, "primary") } returns userGlobal

            shouldThrow<ResponseStatusException> {
                newService(repo).create(setting(namespaceId = nsId, userId = userId, name = "primary", authType = AuthType.API_KEY))
            }.statusCode.value() shouldBe 409
        }

        "create allows same name across layers when authType matches" {
            val repo = repoWithNoConflicts()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()

            val service = newService(repo)
            service.create(setting(namespaceId = nsId, userId = null, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))
            // Same authType — should merge cleanly at reconciliation, no 409.
            service.create(setting(namespaceId = null, userId = userId, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))
            service.create(setting(namespaceId = nsId, userId = userId, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))

            verify(exactly = 3) { repo.save(any()) }
        }

        // -------------------------------------------------------------------------
        // OAUTH_MCP_DISCOVERABLE
        // -------------------------------------------------------------------------

        "create and findById round-trip works for OAUTH_MCP_DISCOVERABLE" {
            val repo = repoWithNoConflicts()
            val service = newService(repo)
            val saved =
                service.create(
                    setting(
                        authType = AuthType.OAUTH_MCP_DISCOVERABLE,
                        data = mapOf("resourceUrl" to "https://mcp.example.com/sse", "scopes" to "read write"),
                    ),
                )
            every { repo.findByIds(listOf(saved.metadata.id), false) } returns listOf(saved)
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.authType shouldBe AuthType.OAUTH_MCP_DISCOVERABLE
            found.data["resourceUrl"] shouldBe "https://mcp.example.com/sse"
            found.data["scopes"] shouldBe "read write"
        }

        "cross-layer authType consistency guard works for OAUTH_MCP_DISCOVERABLE" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val nsShared =
                setting(namespaceId = nsId, userId = null, name = "mcp-setting", authType = AuthType.OAUTH_MCP_DISCOVERABLE)
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, userId, "mcp-setting") } returns null
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, null, "mcp-setting") } returns nsShared

            shouldThrow<ResponseStatusException> {
                newService(repo).create(setting(namespaceId = nsId, userId = userId, name = "mcp-setting", authType = AuthType.API_KEY))
            }.statusCode.value() shouldBe 409
        }

        "OAUTH_MCP_DISCOVERABLE merges across layers correctly" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // Platform provides resourceUrl; namespace-shared provides clientId
            val platform =
                setting(
                    namespaceId = null,
                    userId = null,
                    name = "mcp-setting",
                    authType = AuthType.OAUTH_MCP_DISCOVERABLE,
                    data = mapOf("resourceUrl" to "https://mcp.example.com"),
                )
            val nsShared =
                setting(
                    namespaceId = nsId,
                    userId = null,
                    name = "mcp-setting",
                    authType = AuthType.OAUTH_MCP_DISCOVERABLE,
                    data = mapOf("clientId" to "ns-client-id"),
                )
            every { repo.findAllForScope(nsId, userId) } returns listOf(platform, nsShared)

            val resolved = newService(repo).resolveAuthSetting(nsId, userId, "mcp-setting")
            resolved.data["resourceUrl"] shouldBe "https://mcp.example.com"
            resolved.data["clientId"] shouldBe "ns-client-id"
        }

        "create user-global allows same name as another user's user-global with different authType (cross-user is by-design)" {
            val repo = repoWithNoConflicts()
            val userB = UUID.randomUUID()

            val saved = newService(repo).create(setting(namespaceId = null, userId = userB, name = "primary", authType = AuthType.API_KEY))
            saved.shouldNotBeNull()
        }

        "update rejects when renaming would collide with a different-authType layer" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val nsShared = setting(namespaceId = nsId, userId = null, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE)
            val mine = setting(namespaceId = nsId, userId = userId, name = "secondary", authType = AuthType.API_KEY)
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, userId, "primary") } returns null
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, null, "primary") } returns nsShared

            shouldThrow<ResponseStatusException> {
                newService(repo).update(mine.withName("primary"))
            }.statusCode.value() shouldBe 409
        }

        // -------------------------------------------------------------------------
        // resolveAuthSetting — single-query fold
        // -------------------------------------------------------------------------

        "resolveAuthSetting returns platform layer when no other layer exists" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform = setting(namespaceId = null, userId = null, name = "github", data = mapOf("clientId" to "platform-id"))
            every { repo.findAllForScope(nsId, userId) } returns listOf(platform)

            val resolved = newService(repo).resolveAuthSetting(nsId, userId, "github")

            resolved.data["clientId"] shouldBe "platform-id"
            resolved.namespaceId shouldBe null
            resolved.userId shouldBe null
        }

        "resolveAuthSetting namespace-shared overrides platform" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform = setting(namespaceId = null, userId = null, name = "github", data = mapOf("clientId" to "platform-id"))
            val nsShared = setting(namespaceId = nsId, userId = null, name = "github", data = mapOf("clientId" to "ns-id"))
            every { repo.findAllForScope(nsId, userId) } returns listOf(platform, nsShared)

            val resolved = newService(repo).resolveAuthSetting(nsId, userId, "github")

            resolved.data["clientId"] shouldBe "ns-id"
        }

        "resolveAuthSetting user-global overrides platform but namespace-shared overrides user-global" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform = setting(namespaceId = null, userId = null, name = "github", data = mapOf("clientId" to "platform-id"))
            val userGlobal = setting(namespaceId = null, userId = userId, name = "github", data = mapOf("clientId" to "user-id"))
            val nsShared = setting(namespaceId = nsId, userId = null, name = "github", data = mapOf("clientId" to "ns-id"))
            every { repo.findAllForScope(nsId, userId) } returns listOf(platform, userGlobal, nsShared)

            // namespace-shared (rank 2) wins over user-global (rank 1)
            val resolved = newService(repo).resolveAuthSetting(nsId, userId, "github")
            resolved.data["clientId"] shouldBe "ns-id"
        }

        "resolveAuthSetting user×namespace is highest precedence" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform = setting(namespaceId = null, userId = null, name = "github", data = mapOf("clientId" to "platform-id"))
            val nsShared = setting(namespaceId = nsId, userId = null, name = "github", data = mapOf("clientId" to "ns-id"))
            val userGlobal = setting(namespaceId = null, userId = userId, name = "github", data = mapOf("clientId" to "user-id"))
            val userNs = setting(namespaceId = nsId, userId = userId, name = "github", data = mapOf("clientId" to "user-ns-id"))
            every { repo.findAllForScope(nsId, userId) } returns listOf(platform, nsShared, userGlobal, userNs)

            val resolved = newService(repo).resolveAuthSetting(nsId, userId, "github")
            resolved.data["clientId"] shouldBe "user-ns-id"
        }

        "resolveAuthSetting merges fields — base fills keys absent from override" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // Platform provides discoveryUrl; namespace-shared provides clientId
            val platform =
                setting(
                    namespaceId = null,
                    userId = null,
                    name = "github",
                    data =
                        mapOf("discoveryUrl" to "https://platform.discovery"),
                )
            val nsShared = setting(namespaceId = nsId, userId = null, name = "github", data = mapOf("clientId" to "ns-client-id"))
            every { repo.findAllForScope(nsId, userId) } returns listOf(platform, nsShared)

            val resolved = newService(repo).resolveAuthSetting(nsId, userId, "github")
            resolved.data["clientId"] shouldBe "ns-client-id"
            resolved.data["discoveryUrl"] shouldBe "https://platform.discovery"
        }

        "resolveAuthSetting throws ConfigNotFoundException when no layer has the requested name" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val other = setting(namespaceId = nsId, userId = null, name = "other-setting")
            every { repo.findAllForScope(nsId, userId) } returns listOf(other)

            shouldThrow<ConfigNotFoundException> {
                newService(repo).resolveAuthSetting(nsId, userId, "github")
            }
        }

        "resolveAuthSetting result identity comes from base layer" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform = setting(namespaceId = null, userId = null, name = "github", data = mapOf("clientId" to "platform-id"))
            val nsShared = setting(namespaceId = nsId, userId = null, name = "github", data = mapOf("clientId" to "ns-id"))
            every { repo.findAllForScope(nsId, userId) } returns listOf(platform, nsShared)

            val resolved = newService(repo).resolveAuthSetting(nsId, userId, "github")
            // Result identity is from the lowest-rank layer that exists (platform)
            resolved.metadata.id shouldBe platform.metadata.id
        }

        // -------------------------------------------------------------------------
        // Simple pass-through delegation — one test per method
        // -------------------------------------------------------------------------

        "findById delegates to repository.findByIds" {
            val repo = mockk<AuthSettingRepository>()
            val entity = setting()
            every { repo.findByIds(listOf(entity.id), false) } returns listOf(entity)

            newService(repo).findById(entity.id) shouldBe entity
        }

        "findById returns null for unknown id" {
            val repo = mockk<AuthSettingRepository>()
            val id = UUID.randomUUID()
            every { repo.findByIds(listOf(id), false) } returns emptyList()

            newService(repo).findById(id).shouldBeNull()
        }

        "findByParent delegates to repository" {
            val repo = mockk<AuthSettingRepository>()
            val parentId = UUID.randomUUID()
            val entities = listOf(setting())
            every { repo.findByParent(parentId) } returns entities

            newService(repo).findByParent(parentId) shouldBe entities
        }

        "delete delegates to repository" {
            val repo = mockk<AuthSettingRepository>()
            val id = UUID.randomUUID()
            every { repo.delete(id) } returns true

            newService(repo).delete(id) shouldBe true
            verify(exactly = 1) { repo.delete(id) }
        }

        "deleteByParent delegates to repository" {
            val repo = mockk<AuthSettingRepository>()
            val parentId = UUID.randomUUID()
            every { repo.deleteByParent(parentId) } returns 3

            newService(repo).deleteByParent(parentId) shouldBe 3
        }

        "findByNamespaceId delegates to repository" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val entities = listOf(setting(namespaceId = nsId))
            every { repo.findByNamespaceId(nsId) } returns entities

            newService(repo).findByNamespaceId(nsId) shouldBe entities
        }

        "findByUserId delegates to repository" {
            val repo = mockk<AuthSettingRepository>()
            val userId = UUID.randomUUID()
            val entities = listOf(setting(namespaceId = null, userId = userId))
            every { repo.findByUserId(userId) } returns entities

            newService(repo).findByUserId(userId) shouldBe entities
        }

        "findPlatformLevel delegates to repository" {
            val repo = mockk<AuthSettingRepository>()
            val entities = listOf(setting(namespaceId = null, userId = null))
            every { repo.findPlatformLevel() } returns entities

            newService(repo).findPlatformLevel() shouldBe entities
        }

        "findByTriple delegates to repository.findByNamespaceIdAndUserIdAndName" {
            val repo = mockk<AuthSettingRepository>()
            val nsId = UUID.randomUUID()
            val entity = setting(namespaceId = nsId)
            every { repo.findByNamespaceIdAndUserIdAndName(nsId, null, "github") } returns entity

            newService(repo).findByTriple(nsId, null, "github") shouldBe entity
        }
    }
}
