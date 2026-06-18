package io.whozoss.agentos.permissions

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

class PermissionServiceImplSpec :
    StringSpec({

        val mockUserService = mockk<UserService>()
        val mockPermissionRepository = mockk<PermissionRepository>()
        val mockPermissionCache = mockk<PermissionCache>()

        val permissionService =
            PermissionServiceImpl(
                userService = mockUserService,
                permissionRepository = mockPermissionRepository,
                permissionCache = mockPermissionCache,
            )

        val userId = UUID.randomUUID().toString()
        val entityId = UUID.randomUUID().toString()
        val entityType = EntityType.CASE

        beforeTest {
            clearAllMocks()
        }

        "should return true for super-admin users bypassing all checks" {
            // Given
            val adminUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "admin@example.com",
                    email = "admin@example.com",
                    isAdmin = true,
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
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
            every {
                mockPermissionCache.get(
                    "perm:$userId:$entityType:$entityId:${Action.READ}",
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
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
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
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
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

        "should return false when user not found — falls through to direct/transitive checks which deny" {
            // User not found means isAdmin is null/false — no bypass. The code then
            // proceeds to the normal evaluation path, which returns false here because
            // neither direct nor transitive permission is granted.
            every { mockUserService.findById(UUID.fromString(userId)) } returns null
            every { mockPermissionCache.get(any<String>()) } returns null
            every { mockPermissionCache.put(any(), any()) } just Runs
            every {
                mockPermissionRepository.hasDirectPermission(any(), any(), any(), any())
            } returns false
            every {
                mockPermissionRepository.hasTransitivePermission(any(), any(), any(), any())
            } returns false

            val result = permissionService.hasPermission(userId, entityType, entityId, Action.READ)

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
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
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
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
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
            // listEntitiesForUser does not resolve the user — no userService stub needed.
            every {
                mockPermissionRepository.listEntitiesForUser(any(), any(), any())
            } throws RuntimeException("Database error")

            val result = permissionService.listEntitiesForUser(userId, entityType, Action.READ)

            result shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // Platform-scope (entityId = null)
        // -------------------------------------------------------------------------

        "platform-scope READ is granted to any non-admin authenticated user" {
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser

            val result = permissionService.hasPermission(userId, EntityType.AGENT_CONFIG, null, Action.READ)

            result shouldBe true
            // Repository must NOT be consulted — no entityId to query.
            verify(exactly = 0) { mockPermissionRepository.hasDirectPermission(any(), any(), any(), any()) }
            verify(exactly = 0) { mockPermissionCache.get(any<String>()) }
        }

        "platform-scope WRITE is denied for non-admin users" {
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser

            val result = permissionService.hasPermission(userId, EntityType.AGENT_CONFIG, null, Action.WRITE)

            result shouldBe false
            verify(exactly = 0) { mockPermissionRepository.hasDirectPermission(any(), any(), any(), any()) }
        }

        "platform-scope DELETE is denied for non-admin users" {
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser

            val result = permissionService.hasPermission(userId, EntityType.AGENT_CONFIG, null, Action.DELETE)

            result shouldBe false
            verify(exactly = 0) { mockPermissionRepository.hasDirectPermission(any(), any(), any(), any()) }
        }

        "platform-scope WRITE is granted to super-admin via the existing bypass" {
            val adminUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "admin@example.com",
                    email = "admin@example.com",
                    isAdmin = true,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns adminUser

            val result = permissionService.hasPermission(userId, EntityType.AGENT_CONFIG, null, Action.WRITE)

            result shouldBe true
            verify(exactly = 0) { mockPermissionRepository.hasDirectPermission(any(), any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // filterVisibleIds — batch authorization (story 5-3)
        // -------------------------------------------------------------------------

        "filterVisibleIds returns the subset reported visible by the repository" {
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
            val id1 = UUID.randomUUID().toString()
            val id2 = UUID.randomUUID().toString()
            val id3 = UUID.randomUUID().toString()
            every {
                mockPermissionRepository.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1, id2, id3), PermissionRelation.MEMBER)
            } returns setOf(id1, id3)

            permissionService.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1, id2, id3), Action.READ) shouldBe setOf(id1, id3)
        }

        "filterVisibleIds maps WRITE/DELETE actions to ADMIN relation" {
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
            val id1 = UUID.randomUUID().toString()
            every {
                mockPermissionRepository.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1), PermissionRelation.ADMIN)
            } returns setOf(id1)

            permissionService.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1), Action.WRITE) shouldBe setOf(id1)
            permissionService.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1), Action.DELETE) shouldBe setOf(id1)

            verify(exactly = 2) {
                mockPermissionRepository.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1), PermissionRelation.ADMIN)
            }
        }

        "filterVisibleIds short-circuits to empty set on empty input WITHOUT touching the repository" {
            val result = permissionService.filterVisibleIds(userId, EntityType.AGENT_CONFIG, emptyList(), Action.READ)

            result shouldBe emptySet()
            verify(exactly = 0) { mockPermissionRepository.filterVisibleIds(any(), any(), any(), any()) }
        }

        "filterVisibleIds returns empty set when no candidate id is visible" {
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
            val id1 = UUID.randomUUID().toString()
            val id2 = UUID.randomUUID().toString()
            every {
                mockPermissionRepository.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1, id2), PermissionRelation.MEMBER)
            } returns emptySet()

            permissionService.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1, id2), Action.READ) shouldBe emptySet()
        }

        "filterVisibleIds returns empty set (fail-closed) when the repository throws" {
            val regularUser =
                User(
                    metadata = EntityMetadata(id = UUID.fromString(userId)),
                    externalId = "user@example.com",
                    email = "user@example.com",
                    isAdmin = false,
                )
            every { mockUserService.findById(UUID.fromString(userId)) } returns regularUser
            val id1 = UUID.randomUUID().toString()
            every {
                mockPermissionRepository.filterVisibleIds(any(), any(), any(), any())
            } throws RuntimeException("Cypher failure")

            permissionService.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1), Action.READ) shouldBe emptySet()
        }

        "filterVisibleIds delegates to the repository for super-admin (no service-level bypass)" {
            // Super-admin access to platform entities is handled inside the Cypher query
            // (u.isAdmin = true AND checkPlatform AND e.namespaceId IS NULL), not by a
            // service-level short-circuit. Removed entities are filtered by the query for
            // everyone, including super-admins.
            val id1 = UUID.randomUUID().toString()
            val id2 = UUID.randomUUID().toString()
            every {
                mockPermissionRepository.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1, id2), PermissionRelation.MEMBER)
            } returns setOf(id1, id2)

            val result = permissionService.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1, id2), Action.READ)

            result shouldBe setOf(id1, id2)
            verify(exactly = 1) { mockPermissionRepository.filterVisibleIds(userId, EntityType.AGENT_CONFIG, listOf(id1, id2), PermissionRelation.MEMBER) }
        }
    })
