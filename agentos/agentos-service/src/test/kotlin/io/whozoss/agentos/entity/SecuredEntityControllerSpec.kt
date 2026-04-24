package io.whozoss.agentos.entity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.BlockingPermissionService
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Tests pour SecuredEntityController.
 * Vérifie que les permissions sont correctement appliquées sur toutes les méthodes CRUD.
 */
class SecuredEntityControllerSpec : StringSpec({

    // Test entity et resource pour les tests
    data class TestEntity(
        override val metadata: EntityMetadata = EntityMetadata(),
        val namespaceId: UUID = UUID.randomUUID()
    ) : Entity

    data class TestResource(
        val id: UUID? = null,
        val namespaceId: UUID
    )

    // Implementation concrète pour les tests
    class TestSecuredController(
        service: EntityService<TestEntity, UUID>,
        userService: UserService,
        permissionService: BlockingPermissionService,
        private val entityType: String = "TestEntity"
    ) : SecuredEntityController<TestEntity, UUID, TestResource>(service, userService, permissionService) {

        override fun getEntityType(): String = entityType

        override fun toResource(entity: TestEntity): TestResource = TestResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId
        )

        override fun toDomain(resource: TestResource): TestEntity = TestEntity(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId
        )
    }

    val mockService = mockk<EntityService<TestEntity, UUID>>()
    val mockUserService = mockk<UserService>()
    val mockPermissionService = mockk<BlockingPermissionService>()

    val currentUser = User(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        externalId = "test@example.com",
        email = "test@example.com",
        isAdmin = false
    )

    val controller = TestSecuredController(mockService, mockUserService, mockPermissionService)

    beforeTest {
        clearAllMocks()
        every { mockUserService.getCurrentUser() } returns currentUser
    }

    "getById should return 404 when entity does not exist" {
        // Given
        val entityId = UUID.randomUUID()
        every { mockService.findById(entityId) } returns null

        // When/Then
        shouldThrow<ResourceNotFoundException> {
            controller.getById(entityId)
        }.message shouldBe "Entity not found: $entityId"
    }

    "getById should return 404 when user has no READ permission" {
        // Given
        val entity = TestEntity()
        every { mockService.findById(entity.id) } returns entity
        every {
            mockPermissionService.hasPermission(
                currentUser.id.toString(),
                "TestEntity",
                entity.id.toString(),
                Action.READ
            )
        } returns false

        // When/Then
        shouldThrow<ResourceNotFoundException> {
            controller.getById(entity.id)
        }.message shouldBe "Entity not found: ${entity.id}"
    }

    "getById should return entity when user has READ permission" {
        // Given
        val entity = TestEntity()
        every { mockService.findById(entity.id) } returns entity
        every {
            mockPermissionService.hasPermission(
                currentUser.id.toString(),
                "TestEntity",
                entity.id.toString(),
                Action.READ
            )
        } returns true

        // When
        val result = controller.getById(entity.id)

        // Then
        result.id shouldBe entity.id
        result.namespaceId shouldBe entity.namespaceId
    }

    "getByIds should filter out entities without READ permission" {
        // Given
        val entity1 = TestEntity()
        val entity2 = TestEntity()
        val entity3 = TestEntity()
        val ids = listOf(entity1.id, entity2.id, entity3.id)

        every { mockService.findByIds(ids) } returns listOf(entity1, entity2, entity3)

        // User has permission only for entity1 and entity3
        every {
            mockPermissionService.hasPermission(
                currentUser.id.toString(),
                "TestEntity",
                entity1.id.toString(),
                Action.READ
            )
        } returns true
        every {
            mockPermissionService.hasPermission(
                currentUser.id.toString(),
                "TestEntity",
                entity2.id.toString(),
                Action.READ
            )
        } returns false
        every {
            mockPermissionService.hasPermission(
                currentUser.id.toString(),
                "TestEntity",
                entity3.id.toString(),
                Action.READ
            )
        } returns true

        // When
        val result = controller.getByIds(ids)

        // Then
        result.size shouldBe 2
        result.map { it.id } shouldBe listOf(entity1.id, entity3.id)
    }

    "update should return 403 when user has no WRITE permission" {
        // Given
        val entity = TestEntity()
        val resource = TestResource(id = entity.id, namespaceId = entity.namespaceId)

        every { mockService.findById(entity.id) } returns entity
        every {
            mockPermissionService.hasPermission(
                currentUser.id.toString(),
                "TestEntity",
                entity.id.toString(),
                Action.WRITE
            )
        } returns false

        // When/Then
        val exception = shouldThrow<ResponseStatusException> {
            controller.update(entity.id, resource)
        }
        exception.statusCode.value() shouldBe 403
        exception.reason shouldBe "Access denied"
    }

    "delete should return 403 when user has no DELETE permission" {
        // Given
        val entity = TestEntity()
        every { mockService.findById(entity.id) } returns entity
        every {
            mockPermissionService.hasPermission(
                currentUser.id.toString(),
                "TestEntity",
                entity.id.toString(),
                Action.DELETE
            )
        } returns false

        // When/Then
        val exception = shouldThrow<ResponseStatusException> {
            controller.delete(entity.id)
        }
        exception.statusCode.value() shouldBe 403
        exception.reason shouldBe "Access denied"
    }

    "super-admin should bypass all permission checks" {
        // Given
        val superAdmin = currentUser.copy(isAdmin = true)
        every { mockUserService.getCurrentUser() } returns superAdmin

        val entity = TestEntity()
        every { mockService.findById(entity.id) } returns entity

        // PermissionService should handle super-admin bypass internally
        every {
            mockPermissionService.hasPermission(any(), any(), any(), any())
        } returns true

        // When
        val result = controller.getById(entity.id)

        // Then
        result.id shouldBe entity.id
    }
})