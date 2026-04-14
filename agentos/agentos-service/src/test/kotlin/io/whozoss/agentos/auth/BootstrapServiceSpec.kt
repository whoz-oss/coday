package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityConfigProperties
import io.whozoss.agentos.security.SecurityMode
import io.whozoss.agentos.security.SecurityService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

class BootstrapServiceSpec : StringSpec({
    val userService = mockk<UserService>()
    val roleRepository = mockk<RoleRepository>()
    val namespaceService = mockk<NamespaceService>()
    val securityService = mockk<SecurityService>()

    fun buildService(mode: SecurityMode = SecurityMode.LOCAL, permissive: Boolean = true): BootstrapService =
        BootstrapService(
            userService,
            roleRepository,
            SecurityConfigProperties(mode = mode, permissive = permissive),
            namespaceService,
            securityService,
        )

    val userId = UUID.randomUUID()
    val user = User(metadata = EntityMetadata(id = userId), externalId = "localuser")
    val nsId = UUID.randomUUID()
    val namespace = Namespace(metadata = EntityMetadata(id = nsId), name = "ns-1")

    beforeEach {
        clearMocks(userService, roleRepository, namespaceService, securityService)
    }

    "auto-root in LOCAL mode when no root exists" {
        every { userService.findAll() } returns listOf(user)
        every { securityService.resolveCurrentIdentity() } returns "localuser"
        every { userService.resolveOrCreateByExternalId("localuser") } returns user
        every { roleRepository.setRoot(userId.toString(), true) } just Runs
        every { namespaceService.findAll() } returns emptyList()
        every { roleRepository.findNamespaceRole(any(), any()) } returns null

        buildService(mode = SecurityMode.LOCAL).onApplicationReady()

        verify(exactly = 1) { roleRepository.setRoot(userId.toString(), true) }
    }

    "skip auto-root if root already exists" {
        val rootUser = User(metadata = EntityMetadata(id = userId), externalId = "root", isRoot = true)
        every { userService.findAll() } returns listOf(rootUser)
        every { namespaceService.findAll() } returns emptyList()

        buildService(mode = SecurityMode.LOCAL).onApplicationReady()

        verify(exactly = 0) { roleRepository.setRoot(any(), any()) }
    }

    "AUTH mode does not auto-root (logs warning only)" {
        every { userService.findAll() } returns listOf(user)
        every { namespaceService.findAll() } returns emptyList()
        every { roleRepository.findNamespaceRole(any(), any()) } returns null

        buildService(mode = SecurityMode.AUTH).onApplicationReady()

        verify(exactly = 0) { roleRepository.setRoot(any(), any()) }
    }

    "bootstrap assigns ADMIN to existing users on all namespaces" {
        val rootUser = User(metadata = EntityMetadata(id = userId), externalId = "admin", isRoot = true)
        every { userService.findAll() } returns listOf(rootUser)
        every { namespaceService.findAll() } returns listOf(namespace)
        every { roleRepository.findNamespaceRole(userId.toString(), nsId.toString()) } returns null
        every { roleRepository.assignNamespaceRole(any(), any(), any(), any()) } just Runs

        buildService(mode = SecurityMode.LOCAL).onApplicationReady()

        verify(exactly = 1) {
            roleRepository.assignNamespaceRole(userId.toString(), nsId.toString(), NamespaceRole.ADMIN, "bootstrap")
        }
    }

    "bootstrap skips users who already have a role" {
        val rootUser = User(metadata = EntityMetadata(id = userId), externalId = "admin", isRoot = true)
        every { userService.findAll() } returns listOf(rootUser)
        every { namespaceService.findAll() } returns listOf(namespace)
        every { roleRepository.findNamespaceRole(userId.toString(), nsId.toString()) } returns NamespaceRole.OWNER

        buildService(mode = SecurityMode.LOCAL).onApplicationReady()

        verify(exactly = 0) { roleRepository.assignNamespaceRole(any(), any(), any(), any()) }
    }
})
