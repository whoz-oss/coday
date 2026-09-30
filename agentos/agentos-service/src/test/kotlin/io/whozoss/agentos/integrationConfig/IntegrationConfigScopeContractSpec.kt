package io.whozoss.agentos.integrationConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.testutil.yamlExportMapper
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/** Real controller and service: only identity and permission dependencies are mocked. */
class IntegrationConfigScopeContractSpec : StringSpec({
    val namespaceId = UUID.randomUUID()
    val otherNamespaceId = UUID.randomUUID()
    val callerId = UUID.randomUUID()
    val otherUserId = UUID.randomUUID()

    fun config(name: String, namespaceId: UUID?, userId: UUID?) = IntegrationConfig(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        userId = userId,
        name = name,
        integrationType = "JIRA",
    )

    val platform = config("company-jira", null, null)
    val namespace = config("namespace-jira", namespaceId, null)
    val userGlobal = config("personal-jira", null, callerId)
    val userNamespace = config("personal-namespace-jira", namespaceId, callerId)
    val userOtherNamespace = config("personal-other-namespace-jira", otherNamespaceId, callerId)
    val otherUser = config("other-user-jira", null, otherUserId)
    val otherUserNamespace = config("other-user-namespace-jira", namespaceId, otherUserId)
    val personal = listOf(userGlobal, userNamespace, userOtherNamespace)

    data class ScopeCase(
        val description: String,
        val namespaceParam: String?,
        val userParam: String?,
        val expected: List<IntegrationConfig>,
        val canReadNamespace: Boolean = true,
    )

    listOf(
        ScopeCase("no parameters returns all caller overlays", null, null, personal),
        ScopeCase("namespaceId=none alone returns platform configs", "none", null, listOf(platform)),
        ScopeCase("namespaceId=NONE alone also returns platform configs", "NONE", null, listOf(platform)),
        ScopeCase("userId=me without namespace returns all caller overlays", null, "me", personal),
        ScopeCase("namespaceId=none with userId=me returns user-global configs", "none", "me", listOf(userGlobal)),
        ScopeCase("namespaceId alone returns shared namespace configs", namespaceId.toString(), null, listOf(namespace)),
        ScopeCase("namespaceId with userId=me returns the caller's namespace overlay", namespaceId.toString(), "me", listOf(userNamespace)),
        ScopeCase("namespaceId alone without READ permission returns nothing", namespaceId.toString(), null, emptyList(), false),
    ).forEach { case ->
        case.description {
            val repository = InMemoryIntegrationConfigRepository()
            listOf(platform, namespace, userGlobal, userNamespace, userOtherNamespace, otherUser, otherUserNamespace)
                .forEach { repository.save(it) }
            val service = IntegrationConfigServiceImpl(repository, IntegrationConfigMergeStrategy())
            val userService = mockk<UserService>()
            every { userService.getCurrentUser() } returns User(
                metadata = EntityMetadata(id = callerId),
                externalId = "alice@example.com",
                email = "alice@example.com",
                isAdmin = false,
            )
            val permissionService = mockk<PermissionService>()
            every {
                permissionService.hasPermission(callerId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns case.canReadNamespace
            val controller = IntegrationConfigController(
                service,
                mockk<NamespaceService>(),
                userService,
                permissionService,
                IntegrationConfigScopePolicy(IntegrationsProperties()),
                yamlExportMapper(),
            )

            controller.list(case.namespaceParam, case.userParam).map { it.id } shouldContainExactlyInAnyOrder
                case.expected.map { it.id }
        }
    }
})
