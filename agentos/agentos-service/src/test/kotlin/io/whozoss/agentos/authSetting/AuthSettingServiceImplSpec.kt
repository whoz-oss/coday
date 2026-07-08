package io.whozoss.agentos.authSetting

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.AuthType
import io.whozoss.agentos.sdk.authSetting.authSettingFromDataMap
import io.whozoss.agentos.sdk.authSetting.toDataMap
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

// Convenience accessor for tests that reason about the flat data map.
private val AuthSetting.data: Map<String, String> get() = toDataMap()

class AuthSettingServiceImplSpec : StringSpec() {
    // PermissionService and UserService are only used by findFiltered, which is not
    // exercised in this spec (tested via controller + integration tests).
    // Relaxed mocks satisfy the constructor without interfering with the tested methods.
    private fun newService() = AuthSettingServiceImpl(
        InMemoryAuthSettingRepository(),
        AuthSettingMergeStrategy(),
        mockk<PermissionService>(relaxed = true),
        mockk<UserService>(relaxed = true),
    )

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
        // Scope invariant
        // -------------------------------------------------------------------------

        "create succeeds with both namespaceId and userId null (platform scope)" {
            val service = newService()
            // Platform-level entities have namespaceId=null AND userId=null.
            // The controller enforces the super-admin permission check; the service
            // itself allows all scope combinations.
            val saved = service.create(setting(namespaceId = null, userId = null))
            saved.shouldNotBeNull()
        }

        "create succeeds with namespaceId only" {
            val service = newService()
            val saved = service.create(setting(namespaceId = UUID.randomUUID(), userId = null))
            saved.shouldNotBeNull()
        }

        "create succeeds with userId only" {
            val service = newService()
            val saved = service.create(setting(namespaceId = null, userId = UUID.randomUUID()))
            saved.shouldNotBeNull()
        }

        "create succeeds with both namespaceId and userId" {
            val service = newService()
            val saved = service.create(setting(namespaceId = UUID.randomUUID(), userId = UUID.randomUUID()))
            saved.shouldNotBeNull()
        }

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create and findById returns the same setting" {
            val service = newService()
            val saved = service.create(setting())
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.name shouldBe "github"
            found.authType shouldBe AuthType.OAUTH_DISCOVERABLE
        }

        "findById returns null for unknown id" {
            val service = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // findByNamespaceId
        // -------------------------------------------------------------------------

        "findByNamespaceId returns only settings for the given namespace" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(setting(namespaceId = nsA, name = "github"))
            service.create(setting(namespaceId = nsA, name = "gitlab"))
            service.create(setting(namespaceId = nsB, name = "jira"))

            service.findByNamespaceId(nsA) shouldHaveSize 2
            service.findByNamespaceId(nsB) shouldHaveSize 1
            service.findByNamespaceId(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByNamespaceId returns settings sorted by name" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.create(setting(namespaceId = nsId, name = "jira"))
            service.create(setting(namespaceId = nsId, name = "github"))
            service.create(setting(namespaceId = nsId, name = "gitlab"))

            service.findByNamespaceId(nsId).map { it.name } shouldBe listOf("github", "gitlab", "jira")
        }

        // -------------------------------------------------------------------------
        // findByUserId
        // -------------------------------------------------------------------------

        "findByUserId returns only settings for the given user" {
            val service = newService()
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()

            service.create(setting(namespaceId = null, userId = userA, name = "github"))
            service.create(setting(namespaceId = null, userId = userA, name = "gitlab"))
            service.create(setting(namespaceId = null, userId = userB, name = "jira"))

            service.findByUserId(userA) shouldHaveSize 2
            service.findByUserId(userB) shouldHaveSize 1
            service.findByUserId(UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Uniqueness constraint
        // -------------------------------------------------------------------------

        "create throws 409 when (namespaceId, userId, name) already exists" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(setting(namespaceId = nsId, userId = null, name = "github"))

            shouldThrow<ResponseStatusException> {
                service.create(setting(namespaceId = nsId, userId = null, name = "github"))
            }.statusCode.value() shouldBe 409
        }

        "create allows same name for different scopes" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            val userId = UUID.randomUUID()

            service.create(setting(namespaceId = nsA, userId = null, name = "github"))
            service.create(setting(namespaceId = nsB, userId = null, name = "github"))
            service.create(setting(namespaceId = null, userId = userId, name = "github"))
            service.create(setting(namespaceId = nsA, userId = userId, name = "github"))

            service.findByNamespaceId(nsA) shouldHaveSize 1 // namespace-shared only (userId IS NULL filter)
            service.findByNamespaceId(nsB) shouldHaveSize 1
            service.findByUserId(userId) shouldHaveSize 2 // user-only + namespace+user
        }

        // -------------------------------------------------------------------------
        // data storage
        // -------------------------------------------------------------------------

        "data is stored and retrieved" {
            val service = newService()
            val saved = service.create(setting(data = mapOf("clientId" to "my-client", "clientSecret" to "my-secret")))
            val found = service.findById(saved.metadata.id)
            found?.data shouldBe mapOf("clientId" to "my-client", "clientSecret" to "my-secret")
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "delete soft-deletes the setting" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val s = service.create(setting(namespaceId = nsId))

            service.delete(s.metadata.id) shouldBe true
            service.findById(s.metadata.id).shouldBeNull()
            service.findByNamespaceId(nsId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val service = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "update replaces the setting" {
            val service = newService()
            val original = service.create(setting(data = mapOf("clientId" to "old-id")))
            val updated = service.update(original.withData(mapOf("clientId" to "new-id")))

            updated.data["clientId"] shouldBe "new-id"
            service.findById(original.metadata.id)?.data?.get("clientId") shouldBe "new-id"
        }

        "update allows renaming to the same name (no false conflict with self)" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val original = service.create(setting(namespaceId = nsId, name = "github"))

            // updating with same name should not throw
            val updated = service.update(original.withData(mapOf("clientId" to "new-id")))
            updated.name shouldBe "github"
        }

        "update throws 409 when renaming conflicts with another setting in the same scope" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(setting(namespaceId = nsId, name = "gitlab"))
            val toUpdate = service.create(setting(namespaceId = nsId, name = "github"))

            shouldThrow<ResponseStatusException> {
                service.update(toUpdate.withName("gitlab"))
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
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(namespaceId = nsId, userId = null, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))

            shouldThrow<ResponseStatusException> {
                service.create(setting(namespaceId = nsId, userId = userId, name = "primary", authType = AuthType.API_KEY))
            }.statusCode.value() shouldBe 409
        }

        "create user-global rejects when same-user user×ns layer has same name with different authType" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(namespaceId = nsId, userId = userId, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))

            shouldThrow<ResponseStatusException> {
                service.create(setting(namespaceId = null, userId = userId, name = "primary", authType = AuthType.API_KEY))
            }.statusCode.value() shouldBe 409
        }

        "create user×ns rejects when same-user user-global layer has same name with different authType" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(namespaceId = null, userId = userId, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))

            shouldThrow<ResponseStatusException> {
                service.create(setting(namespaceId = nsId, userId = userId, name = "primary", authType = AuthType.API_KEY))
            }.statusCode.value() shouldBe 409
        }

        "create allows same name across layers when authType matches" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()

            service.create(setting(namespaceId = nsId, userId = null, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))
            // Same authType — should merge cleanly at reconciliation, no 409.
            service.create(setting(namespaceId = null, userId = userId, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))
            service.create(setting(namespaceId = nsId, userId = userId, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))

            service.findByUserId(userId) shouldHaveSize 2
        }

        "create user-global allows same name as another user's user-global with different authType (cross-user is by-design)" {
            val service = newService()
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            service.create(setting(namespaceId = null, userId = userA, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))

            // Different user → no overlap at reconciliation, no conflict.
            val saved = service.create(setting(namespaceId = null, userId = userB, name = "primary", authType = AuthType.API_KEY))
            saved.shouldNotBeNull()
        }

        // -------------------------------------------------------------------------
        // resolveAuthSetting — single-query fold
        // -------------------------------------------------------------------------

        "resolveAuthSetting returns platform layer when no other layer exists" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(
                namespaceId = null, userId = null, name = "github",
                data = mapOf("clientId" to "platform-id"),
            ))

            val resolved = service.resolveAuthSetting(nsId, userId, "github")
            resolved.data["clientId"] shouldBe "platform-id"
            resolved.namespaceId shouldBe null
            resolved.userId shouldBe null
        }

        "resolveAuthSetting namespace-shared overrides platform" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(namespaceId = null, userId = null, name = "github",
                data = mapOf("clientId" to "platform-id")))
            service.create(setting(namespaceId = nsId, userId = null, name = "github",
                data = mapOf("clientId" to "ns-id")))

            val resolved = service.resolveAuthSetting(nsId, userId, "github")
            resolved.data["clientId"] shouldBe "ns-id"
        }

        "resolveAuthSetting user-global overrides platform but namespace-shared overrides user-global" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(namespaceId = null, userId = null, name = "github",
                data = mapOf("clientId" to "platform-id")))
            service.create(setting(namespaceId = null, userId = userId, name = "github",
                data = mapOf("clientId" to "user-id")))
            service.create(setting(namespaceId = nsId, userId = null, name = "github",
                data = mapOf("clientId" to "ns-id")))

            // namespace-shared (rank 2) wins over user-global (rank 1)
            val resolved = service.resolveAuthSetting(nsId, userId, "github")
            resolved.data["clientId"] shouldBe "ns-id"
        }

        "resolveAuthSetting user×namespace is highest precedence" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(namespaceId = null, userId = null, name = "github",
                data = mapOf("clientId" to "platform-id")))
            service.create(setting(namespaceId = nsId, userId = null, name = "github",
                data = mapOf("clientId" to "ns-id")))
            service.create(setting(namespaceId = null, userId = userId, name = "github",
                data = mapOf("clientId" to "user-id")))
            service.create(setting(namespaceId = nsId, userId = userId, name = "github",
                data = mapOf("clientId" to "user-ns-id")))

            val resolved = service.resolveAuthSetting(nsId, userId, "github")
            resolved.data["clientId"] shouldBe "user-ns-id"
        }

        "resolveAuthSetting merges fields — base fills keys absent from override" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            // Platform provides discoveryUrl; namespace-shared provides clientId
            service.create(setting(namespaceId = null, userId = null, name = "github",
                data = mapOf("discoveryUrl" to "https://platform.discovery")))
            service.create(setting(namespaceId = nsId, userId = null, name = "github",
                data = mapOf("clientId" to "ns-client-id")))

            val resolved = service.resolveAuthSetting(nsId, userId, "github")
            resolved.data["clientId"] shouldBe "ns-client-id"
            resolved.data["discoveryUrl"] shouldBe "https://platform.discovery"
        }

        "resolveAuthSetting throws ConfigNotFoundException when no layer has the requested name" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(namespaceId = nsId, userId = null, name = "other-setting"))

            shouldThrow<ConfigNotFoundException> {
                service.resolveAuthSetting(nsId, userId, "github")
            }
        }

        "update rejects when renaming would collide with a different-authType layer" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            service.create(setting(namespaceId = nsId, userId = null, name = "primary", authType = AuthType.OAUTH_DISCOVERABLE))
            val mine = service.create(setting(namespaceId = nsId, userId = userId, name = "secondary", authType = AuthType.API_KEY))

            shouldThrow<ResponseStatusException> {
                service.update(mine.withName("primary"))
            }.statusCode.value() shouldBe 409
        }

        // -------------------------------------------------------------------------
        // Merge result identity preservation
        // -------------------------------------------------------------------------

        "resolveAuthSetting result identity comes from base layer" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val platform = service.create(setting(namespaceId = null, userId = null, name = "github",
                data = mapOf("clientId" to "platform-id")))
            service.create(setting(namespaceId = nsId, userId = null, name = "github",
                data = mapOf("clientId" to "ns-id")))

            val resolved = service.resolveAuthSetting(nsId, userId, "github")
            // Result identity is from the lowest-rank layer that exists (platform)
            resolved.metadata.id shouldBe platform.metadata.id
        }
    }
}
