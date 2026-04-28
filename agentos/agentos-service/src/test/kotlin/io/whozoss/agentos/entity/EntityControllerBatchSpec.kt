package io.whozoss.agentos.entity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for the batch authorization pattern factorised in [EntityController.getByIds]
 * by story 5-4 (replaces the duplicated implementation introduced by story 5-3).
 *
 * Uses a minimal in-test fixture controller `TestController` that extends [EntityController]
 * with stub `entityType`, `toResource`, and `toDomain`. The fixture exists only inside this
 * spec to exercise the inherited `getByIds` behaviour without standing up a Spring context.
 *
 * Out of scope here :
 * - `@PreAuthorize("isAuthenticated()")` — Spring AOP only, covered by `MethodSecurityIntegrationSpec`.
 * - Log WARN on malformed UUID — verified manually by reading the code path; non-deterministic
 *   to assert without a custom log appender.
 */
class EntityControllerBatchSpec : StringSpec({

    val service = mockk<EntityService<TestEntity, UUID>>()
    val userService = mockk<UserService>()
    val permissionService = mockk<PermissionService>()
    val controller = TestController(service, userService, permissionService)

    val callerId = UUID.randomUUID()
    val superAdmin = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "root@example.com",
        email = "root@example.com",
        isAdmin = true,
    )
    val regularUser = User(
        metadata = EntityMetadata(id = callerId),
        externalId = "user@example.com",
        email = "user@example.com",
        isAdmin = false,
    )

    fun entity(id: UUID = UUID.randomUUID(), tag: String = "tag") =
        TestEntity(metadata = EntityMetadata(id = id), tag = tag)

    beforeTest { clearAllMocks() }

    "getByIds short-circuits to empty list on empty input WITHOUT calling userService or permissionService" {
        controller.getByIds(emptyList()) shouldBe emptyList()
        verify(exactly = 0) { userService.getCurrentUser() }
        verify(exactly = 0) { permissionService.filterVisibleIds(any(), any(), any(), any()) }
        verify(exactly = 0) { service.findByIds(any()) }
    }

    "getByIds returns all matching entities for a super-admin caller (admin bypass — no permissionService call)" {
        val a = entity(tag = "alpha")
        val b = entity(tag = "beta")
        every { userService.getCurrentUser() } returns superAdmin
        every { service.findByIds(setOf(a.id, b.id)) } returns listOf(a, b)

        val result = controller.getByIds(listOf(a.id, b.id))

        result.map { it.tag } shouldContainExactly listOf("alpha", "beta")
        verify(exactly = 0) { permissionService.filterVisibleIds(any(), any(), any(), any()) }
    }

    "getByIds delegates to permissionService.filterVisibleIds for a regular caller" {
        val a = entity(tag = "visible")
        val b = entity(tag = "denied")
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.filterVisibleIds(
                callerId.toString(), "TestEntity", listOf(a.id.toString(), b.id.toString()), Action.READ,
            )
        } returns setOf(a.id.toString())
        every { service.findByIds(setOf(a.id)) } returns listOf(a)

        val result = controller.getByIds(listOf(a.id, b.id))

        result.map { it.tag } shouldContainExactly listOf("visible")
    }

    "getByIds returns empty list for a regular caller with no visible ids (without calling service.findByIds)" {
        val a = entity()
        every { userService.getCurrentUser() } returns regularUser
        every {
            permissionService.filterVisibleIds(any(), any(), any(), any())
        } returns emptySet()

        controller.getByIds(listOf(a.id)) shouldBe emptyList()
        verify(exactly = 0) { service.findByIds(any()) }
    }

    "getByIds returns empty list when admin sees no matching entity (findByIds returns empty)" {
        val a = entity()
        every { userService.getCurrentUser() } returns superAdmin
        every { service.findByIds(setOf(a.id)) } returns emptyList()

        controller.getByIds(listOf(a.id)) shouldBe emptyList()
    }

    "getByIds preserves input order" {
        val a = entity(tag = "first")
        val b = entity(tag = "second")
        val c = entity(tag = "third")
        every { userService.getCurrentUser() } returns superAdmin
        // Service may return in any order — controller must reorder to match input.
        every { service.findByIds(setOf(a.id, b.id, c.id)) } returns listOf(c, a, b)

        val result = controller.getByIds(listOf(a.id, b.id, c.id))

        result.map { it.tag } shouldContainExactly listOf("first", "second", "third")
    }

    "getByIds preserves duplicate input ids in the response" {
        val a = entity(tag = "dup")
        every { userService.getCurrentUser() } returns superAdmin
        every { service.findByIds(setOf(a.id)) } returns listOf(a)

        // Input has 3 copies of the same id ; output also has 3 copies.
        val result = controller.getByIds(listOf(a.id, a.id, a.id))

        result.size shouldBe 3
        result.all { it.tag == "dup" } shouldBe true
    }

    "getByIds drops missing ids without erroring (admin caller, partial findByIds)" {
        val a = entity(tag = "found")
        val missingId = UUID.randomUUID()
        every { userService.getCurrentUser() } returns superAdmin
        every { service.findByIds(setOf(a.id, missingId)) } returns listOf(a)

        val result = controller.getByIds(listOf(a.id, missingId))

        result.map { it.tag } shouldContainExactly listOf("found")
    }

    "getByIds rejects oversized input batches with HTTP 400 (DoS protection)" {
        val oversizedIds = List(EntityController.MAX_BATCH_SIZE + 1) { UUID.randomUUID() }

        val ex = shouldThrow<ResponseStatusException> { controller.getByIds(oversizedIds) }

        ex.statusCode shouldBe HttpStatus.BAD_REQUEST
        verify(exactly = 0) { userService.getCurrentUser() }
        verify(exactly = 0) { service.findByIds(any()) }
    }

    "getByIds accepts exactly MAX_BATCH_SIZE input ids (boundary)" {
        val ids = List(EntityController.MAX_BATCH_SIZE) { UUID.randomUUID() }
        every { userService.getCurrentUser() } returns superAdmin
        every { service.findByIds(ids.toSet()) } returns emptyList()

        // Should not throw — exactly at the cap is allowed.
        controller.getByIds(ids) shouldBe emptyList()
    }

    "getByIds tolerates malformed UUIDs returned by permissionService (silent drop, fail-closed)" {
        val a = entity(tag = "ok")
        every { userService.getCurrentUser() } returns regularUser
        // permissionService returns one valid UUID and one corrupted string — only the
        // valid one survives the parse. The malformed entry triggers a WARN log
        // (not asserted here ; verified via manual code reading + KDoc contract).
        every {
            permissionService.filterVisibleIds(any(), any(), any(), any())
        } returns setOf(a.id.toString(), "not-a-uuid")
        every { service.findByIds(setOf(a.id)) } returns listOf(a)

        val result = controller.getByIds(listOf(a.id))

        result.map { it.tag } shouldContainExactly listOf("ok")
    }
})

// -------------------------------------------------------------------------
// In-test fixtures — minimal Entity / Resource / Controller stubs
// -------------------------------------------------------------------------

internal data class TestEntity(
    override val metadata: EntityMetadata,
    val tag: String,
) : Entity

internal data class TestResource(
    val id: UUID,
    val tag: String,
)

internal class TestController(
    service: EntityService<TestEntity, UUID>,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<TestEntity, UUID, TestResource>(service, userService, permissionService) {
    override val entityType = "TestEntity"
    override fun toResource(entity: TestEntity) = TestResource(entity.metadata.id, entity.tag)
    override fun toDomain(resource: TestResource) = TestEntity(EntityMetadata(id = resource.id), resource.tag)
}
