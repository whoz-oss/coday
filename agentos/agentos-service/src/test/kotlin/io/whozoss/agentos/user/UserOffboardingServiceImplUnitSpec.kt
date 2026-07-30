package io.whozoss.agentos.user

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.userGroup.UserGroupRepository
import java.util.UUID

class UserOffboardingServiceImplUnitSpec : StringSpec({

    fun user(id: UUID, externalId: String = "user-$id@example.com") =
        User(metadata = EntityMetadata(id = id), externalId = externalId)

    "revokeAllAccess removes the user from all groups by externalId" {
        val userId = UUID.randomUUID()
        val target = user(userId, externalId = "alice@example.com")
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)
        every { permissionService.listEntitiesForUser(any(), any(), any()) } returns emptyList()

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeAllAccess(userId)

        verify(exactly = 1) { userGroupRepository.removeUserFromAllGroups("alice@example.com") }
    }

    "revokeAllAccess revokes ADMIN on every namespace where the user is admin" {
        val userId = UUID.randomUUID()
        val target = user(userId)
        val nsAdmin1 = UUID.randomUUID().toString()
        val nsAdmin2 = UUID.randomUUID().toString()
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)
        every {
            permissionService.listEntitiesForUser(userId.toString(), EntityType.NAMESPACE, Action.WRITE)
        } returns listOf(nsAdmin1, nsAdmin2)
        every {
            permissionService.listEntitiesForUser(userId.toString(), EntityType.NAMESPACE, Action.READ)
        } returns listOf(nsAdmin1, nsAdmin2)

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeAllAccess(userId)

        verify(exactly = 1) {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, nsAdmin1, PermissionRelation.ADMIN)
        }
        verify(exactly = 1) {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, nsAdmin2, PermissionRelation.ADMIN)
        }
        verify(exactly = 0) {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, nsAdmin1, PermissionRelation.MEMBER)
        }
    }

    "revokeAllAccess revokes MEMBER on namespaces where the user is a plain member (not admin)" {
        val userId = UUID.randomUUID()
        val target = user(userId)
        val nsAdmin = UUID.randomUUID().toString()
        val nsMember = UUID.randomUUID().toString()
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)
        every {
            permissionService.listEntitiesForUser(userId.toString(), EntityType.NAMESPACE, Action.WRITE)
        } returns listOf(nsAdmin)
        every {
            permissionService.listEntitiesForUser(userId.toString(), EntityType.NAMESPACE, Action.READ)
        } returns listOf(nsAdmin, nsMember)

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeAllAccess(userId)

        verify(exactly = 1) {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, nsAdmin, PermissionRelation.ADMIN)
        }
        verify(exactly = 1) {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, nsMember, PermissionRelation.MEMBER)
        }
        // nsAdmin must not also receive a MEMBER revoke — it was only in the WRITE (admin) set.
        verify(exactly = 0) {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, nsAdmin, PermissionRelation.MEMBER)
        }
    }

    "revokeAllAccess clears the user permission cache" {
        val userId = UUID.randomUUID()
        val target = user(userId)
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)
        every { permissionService.listEntitiesForUser(any(), any(), any()) } returns emptyList()

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeAllAccess(userId)

        verify(exactly = 1) { permissionService.clearUserCache(userId.toString()) }
    }

    "revokeAllAccess is resilient to a single revokePermission failure and still clears the cache" {
        val userId = UUID.randomUUID()
        val target = user(userId)
        val nsAdmin = UUID.randomUUID().toString()
        val userService = mockk<UserService> { every { getById(userId) } returns target }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)
        every {
            permissionService.listEntitiesForUser(userId.toString(), EntityType.NAMESPACE, Action.WRITE)
        } returns listOf(nsAdmin)
        every {
            permissionService.listEntitiesForUser(userId.toString(), EntityType.NAMESPACE, Action.READ)
        } returns listOf(nsAdmin)
        every {
            permissionService.revokePermission(userId.toString(), EntityType.NAMESPACE, nsAdmin, PermissionRelation.ADMIN)
        } throws RuntimeException("boom")

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)
        service.revokeAllAccess(userId)

        verify(exactly = 1) { permissionService.clearUserCache(userId.toString()) }
    }

    "revokeAllAccess throws 404 when the user does not exist" {
        val userId = UUID.randomUUID()
        val userService = mockk<UserService> { every { getById(userId) } throws ResourceNotFoundException("Entity $userId not found") }
        val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>(relaxed = true)

        val service = UserOffboardingServiceImpl(userService, userGroupRepository, permissionService)

        shouldThrow<ResourceNotFoundException> { service.revokeAllAccess(userId) }
        verify(exactly = 0) { userGroupRepository.removeUserFromAllGroups(any()) }
    }
})
