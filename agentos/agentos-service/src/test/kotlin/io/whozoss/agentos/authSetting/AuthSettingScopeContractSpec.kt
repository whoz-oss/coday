package io.whozoss.agentos.authSetting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/** Exercises query parameter parsing and scope selection together, with the real controller and service. */
class AuthSettingScopeContractSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()
        val callerId = UUID.randomUUID()

        fun setting(
            name: String,
            namespaceId: UUID?,
            userId: UUID?,
        ) = authSettingFromDataMap(
            authType = AuthType.OAUTH_DISCOVERABLE,
            data = emptyMap(),
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            userId = userId,
            name = name,
            description = null,
        )

        val platform = setting("company-jira", null, null)
        val userGlobal = setting("personal-jira", null, callerId)
        val userNamespace = setting("personal-namespace-jira", namespaceId, callerId)
        val namespaceShared = setting("namespace-jira", namespaceId, null)

        data class ScopeCase(
            val description: String,
            val namespaceParam: String?,
            val userParam: String?,
            val expected: List<AuthSetting>,
        )

        listOf(
            ScopeCase("no parameters returns all caller overlays", null, null, listOf(userGlobal, userNamespace)),
            ScopeCase("namespaceId=none without userId returns platform settings", "none", null, listOf(platform)),
            ScopeCase("namespaceId=NONE also selects platform settings", "NONE", null, listOf(platform)),
            ScopeCase("userId=me without namespace returns all caller overlays", null, "me", listOf(userGlobal, userNamespace)),
            ScopeCase("namespaceId=none with userId=me returns only user-global settings", "none", "me", listOf(userGlobal)),
            ScopeCase("namespaceId without userId returns only shared namespace settings", namespaceId.toString(), null, listOf(namespaceShared)),
            ScopeCase("namespaceId with userId=me returns only the caller's namespace overlay", namespaceId.toString(), "me", listOf(userNamespace)),
        ).forEach { case ->
            case.description {
                val repository = mockk<AuthSettingRepository>()
                every { repository.findPlatformLevel() } returns listOf(platform)
                every { repository.findByUserId(callerId) } returns listOf(userGlobal, userNamespace)
                every { repository.findByNamespaceId(namespaceId) } returns listOf(namespaceShared, userNamespace)
                val userService = mockk<UserService>()
                every { userService.getCurrentUser() } returns
                    User(
                        metadata = EntityMetadata(id = callerId),
                        externalId = "alice@example.com",
                        email = "alice@example.com",
                    )
                val permissionService = mockk<PermissionService>()
                every {
                    permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
                } returns true
                val service = AuthSettingServiceImpl(repository, AuthSettingMergeStrategy(), permissionService, userService)
                val controller = AuthSettingController(service, mockk<NamespaceService>(), userService, permissionService)

                controller.list(case.namespaceParam, case.userParam).map { it.id } shouldContainExactlyInAnyOrder
                    case.expected.map { it.id }
            }
        }
    })
