package io.whozoss.agentos.permissions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

class PermissionServiceImplSpec : StringSpec({

    val mockUserService = mockk<UserService>()
    val mockPermissionRepository = mockk<PermissionRepository>()
    val mockPermissionCache = mockk<PermissionCache>()

    val permissionService = PermissionServiceImpl(
        userService = mockUserService,
        permissionRepository = mockPermissionRepository,
        permissionCache = mockPermissionCache
    )

    val userId = UUID.randomUUID().toString()
    val entityId = UUID.randomUUID().toString()
    val entityType = "Case"

    beforeTest {
        clearAllMocks()
    }

    "should return true for super-admin users bypassing all checks" {
        // Given
        val adminUser = User(
            metadata = EntityMetadata(id = UUID.fromString(userId)),
            externalId = "admin@example.com",
            email = "admin@example.com",
            isAdmin = true
        )
        every { mockUserService.findById(UUID.fromString(userId)) } returns adminUser

        // When
        val result = permissionService.hasPermission(userId, entityType, entityId, Action.DELETE)

        // Then
        result shouldBe true
        verify(exactly = 0) { mockPermissionCache.get(any<String>()) }
        verify(exactly = 0) { mockPermissionRepository.hasDirectPermission(any(), any(), any(), any()) }
    }

    "should return cached result when available" {
        // Given
        val regularUser = User(
            metadata = EntityMetadata(id = UUID.fromString(userId)),
            externalId = "user@example.com",
            email = "user@example.com",
            isAdmin = false
        )
        every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
        every {
            mockPermissionCache.get(
                "perm:$userId:$entityType:$entityId:${Action.READ}"
            )
        } returns true

        // When
        val result = permissionService.hasPermission(userId, entityType, entityId, Action.READ)

        // Then
        result shouldBe true
        verify(exactly = 0) { mockPermissionRepository.hasDirectPermission(any(), any(), any(), any()) }
    }

    "should check direct permissions when not cached" {
        // Given
        val regularUser = User(
            metadata = EntityMetadata(id = UUID.fromString(userId)),
            externalId = "user@example.com",
            email = "user@example.com",
            isAdmin = false
        )
        every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
        every { mockPermissionCache.get(any<String>()) } returns null
        every { mockPermissionCache.put(any(), any()) } just Runs
        every {
            mockPermissionRepository.hasDirectPermission(userId, entityType, entityId, PermissionRelation.MEMBER)
        } returns true
        every {
            mockPermissionRepository.hasTransitivePermission(any(), any(), any(), any())
        } returns false

        // When
        val result = permissionService.hasPermission(userId, entityType, entityId, Action.READ)

        // Then
        result shouldBe true
        verify { mockPermissionCache.put(any(), true) }
    }

    "should check transitive permissions when direct permission denied" {
        // Given
        val regularUser = User(
            metadata = EntityMetadata(id = UUID.fromString(userId)),
            externalId = "user@example.com",
            email = "user@example.com",
            isAdmin = false
        )
        every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
        every { mockPermissionCache.get(any<String>()) } returns null
        every { mockPermissionCache.put(any(), any()) } just Runs
        every {
            mockPermissionRepository.hasDirectPermission(any(), any(), any(), any())
        } returns false
        every {
            mockPermissionRepository.hasTransitivePermission(userId, entityType, entityId, PermissionRelation.ADMIN)
        } returns true

        // When
        val result = permissionService.hasPermission(userId, entityType, entityId, Action.WRITE)

        // Then
        result shouldBe true
        verify { mockPermissionCache.put(any(), true) }
    }

    "should return false (fail-closed) when user not found" {
        // Given
        every { mockUserService.findById(UUID.fromString(userId)) } returns null
        every { mockPermissionCache.get(any<String>()) } returns null
        every { mockPermissionCache.put(any(), any()) } just Runs
        every {
            mockPermissionRepository.hasDirectPermission(any(), any(), any(), any())
        } returns false
        every {
            mockPermissionRepository.hasTransitivePermission(any(), any(), any(), any())
        } returns false

        // When
        val result = permissionService.hasPermission(userId, entityType, entityId, Action.READ)

        // Then
        result shouldBe false
    }

    "should return false (fail-closed) on invalid UUID" {
        // Given
        val invalidUserId = "not-a-uuid"

        // When
        val result = permissionService.hasPermission(invalidUserId, entityType, entityId, Action.READ)

        // Then
        result shouldBe false
    }

    "should return false (fail-closed) on any exception" {
        // Given
        every { mockUserService.findById(any()) } throws RuntimeException("Database error")

        // When
        val result = permissionService.hasPermission(userId, entityType, entityId, Action.READ)

        // Then
        result shouldBe false
    }

    "should invalidate entire cache when granting permission" {
        // Given
        every {
            mockPermissionRepository.grantPermission(userId, entityType, entityId, PermissionRelation.ADMIN)
        } just Runs
        every { mockPermissionCache.clear() } just Runs

        // When
        permissionService.grantPermission(userId, entityType, entityId, PermissionRelation.ADMIN)

        // Then
        verify { mockPermissionCache.clear() }
        verify { mockPermissionRepository.grantPermission(userId, entityType, entityId, PermissionRelation.ADMIN) }
    }

    "should invalidate entire cache when revoking permission" {
        // Given
        every {
            mockPermissionRepository.revokePermission(userId, entityType, entityId, PermissionRelation.MEMBER)
        } just Runs
        every { mockPermissionCache.clear() } just Runs

        // When
        permissionService.revokePermission(userId, entityType, entityId, PermissionRelation.MEMBER)

        // Then
        verify { mockPermissionCache.clear() }
        verify { mockPermissionRepository.revokePermission(userId, entityType, entityId, PermissionRelation.MEMBER) }
    }

    "READ action should accept MEMBER or ADMIN permission" {
        // Given
        val regularUser = User(
            metadata = EntityMetadata(id = UUID.fromString(userId)),
            externalId = "user@example.com",
            email = "user@example.com",
            isAdmin = false
        )
        every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
        every { mockPermissionCache.get(any<String>()) } returns null
        every { mockPermissionCache.put(any(), any()) } just Runs
        every {
            mockPermissionRepository.hasDirectPermission(userId, entityType, entityId, PermissionRelation.MEMBER)
        } returns true

        // When
        val result = permissionService.hasPermission(userId, entityType, entityId, Action.READ)

        // Then
        result shouldBe true
    }

    "WRITE action should require ADMIN permission" {
        // Given
        val regularUser = User(
            metadata = EntityMetadata(id = UUID.fromString(userId)),
            externalId = "user@example.com",
            email = "user@example.com",
            isAdmin = false
        )
        every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
        every { mockPermissionCache.get(any<String>()) } returns null
        every { mockPermissionCache.put(any(), any()) } just Runs
        every {
            mockPermissionRepository.hasDirectPermission(userId, entityType, entityId, PermissionRelation.ADMIN)
        } returns false
        every {
            mockPermissionRepository.hasTransitivePermission(any(), any(), any(), any())
        } returns false

        // When
        val result = permissionService.hasPermission(userId, entityType, entityId, Action.WRITE)

        // Then
        result shouldBe false
    }

    "should return empty list when listing entities fails" {
        // Given
        val regularUser = User(
            metadata = EntityMetadata(id = UUID.fromString(userId)),
            externalId = "user@example.com",
            email = "user@example.com",
            isAdmin = false
        )
        every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
        every {
            mockPermissionRepository.listEntitiesForUser(any(), any(), any())
        } throws RuntimeException("Database error")

        // When
        val result = permissionService.listEntitiesForUser(userId, entityType, Action.READ)

        // Then
        result shouldBe emptyList()
    }
})
