package io.whozoss.agentos.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.userGroup.UserGroupRepository
import java.util.UUID

class UserOffboardingServiceImplUnitSpec : StringSpec({

    fun user(id: UUID, externalId: String = "user-$id@example.com") =
        User(metadata = EntityMetadata(id = id), externalId = externalId)

    "revokeNamespaceAccess removes the user from groups within that namespace only" {
        val userId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val target = user(userId, externalId = "alice@example.com")
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeNamespaceAccess(userId, namespaceId)

        verify(exactly = 1) { userGroupRepository.removeUserFromGroupsInNamespace("alice@example.com", namespaceId) }
    }

    "revokeNamespaceAccess revokes both ADMIN and MEMBER relations on the given namespace" {
        val userId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val target = user(userId)
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeNamespaceAccess(userId, namespaceId)

        verify(exactly = 1) {
            permissionService.revokePermission(
                userId.toString(),
                io.whozoss.agentos.permissions.EntityType.NAMESPACE,
                namespaceId.toString(),
                PermissionRelation.ADMIN,
            )
        }
        verify(exactly = 1) {
            permissionService.revokePermission(
                userId.toString(),
                io.whozoss.agentos.permissions.EntityType.NAMESPACE,
                namespaceId.toString(),
                PermissionRelation.MEMBER,
            )
        }
    }

    "revokeNamespaceAccess does not touch any other namespace" {
        val userId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val otherNamespaceId = UUID.randomUUID()
        val target = user(userId)
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeNamespaceAccess(userId, namespaceId)

        verify(exactly = 0) {
            permissionService.revokePermission(
                userId.toString(),
                io.whozoss.agentos.permissions.EntityType.NAMESPACE,
                otherNamespaceId.toString(),
                any(),
            )
        }
        verify(exactly = 0) { userGroupRepository.removeUserFromGroupsInNamespace(any(), otherNamespaceId) }
    }

    "revokeNamespaceAccess clears the user permission cache" {
        val userId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val target = user(userId)
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeNamespaceAccess(userId, namespaceId)

        verify(exactly = 1) { permissionService.clearUserCache(userId.toString()) }
    }

    "revokeNamespaceAccess is resilient to a revokePermission failure and still clears the cache" {
        val userId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val target = user(userId)
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)
        every {
            permissionService.revokePermission(
                userId.toString(),
                io.whozoss.agentos.permissions.EntityType.NAMESPACE,
                namespaceId.toString(),
                PermissionRelation.ADMIN,
            )
        } throws RuntimeException("boom")

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeNamespaceAccess(userId, namespaceId)

        verify(exactly = 1) { permissionService.clearUserCache(userId.toString()) }
        // MEMBER revoke must still be attempted despite the ADMIN failure
        verify(exactly = 1) {
            permissionService.revokePermission(
                userId.toString(),
                io.whozoss.agentos.permissions.EntityType.NAMESPACE,
                namespaceId.toString(),
                PermissionRelation.MEMBER,
            )
        }
    }

    "revokeNamespaceAccess throws 404 when the user does not exist" {
        val userId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val userService = mockk<UserService> { every { getById(userId) } throws ResourceNotFoundException("Entity $userId not found") }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)

        shouldThrow<ResourceNotFoundException> { service.revokeNamespaceAccess(userId, namespaceId) }
        verify(exactly = 0) { userGroupRepository.removeUserFromGroupsInNamespace(any(), any()) }
    }
})
