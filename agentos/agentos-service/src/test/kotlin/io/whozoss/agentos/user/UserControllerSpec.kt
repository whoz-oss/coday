package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityService
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Unit tests for [UserController].
 *
 * Exercises all endpoints directly without Spring context: the controller is
 * instantiated with MockK stubs for its two dependencies. This keeps the tests
 * fast and focused on the controller's own responsibilities:
 * - resource ↔ domain conversion
 * - delegation to [UserService]
 * - correct HTTP status / exception on not-found
 * - [SecurityService] delegation for /me
 */
class UserControllerSpec : StringSpec({
    timeout = 5000

    val userService = mockk<UserService>()
    val securityService = mockk<SecurityService>()
    val controller = UserController(userService, securityService)

    fun user(
        id: UUID = UUID.randomUUID(),
        email: String = "alice@example.com",
        externalId: String = email,
        firstname: String? = "Alice",
        lastname: String? = "Smith",
        bio: String? = null,
    ) = User(
        metadata = EntityMetadata(id = id),
        externalId = externalId,
        email = email,
        firstname = firstname,
        lastname = lastname,
        bio = bio,
    )

    // -------------------------------------------------------------------------
    // listAll
    // -------------------------------------------------------------------------

    "listAll returns all users as resources" {
        val u1 = user(email = "alice@example.com")
        val u2 = user(email = "bob@example.com")
        every { userService.findAll() } returns listOf(u1, u2)

        val result = controller.listAll()

        result.map { it.email } shouldBe listOf("alice@example.com", "bob@example.com")
        verify(exactly = 1) { userService.findAll() }
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    "getById returns the resource when found" {
        val u = user()
        every { userService.findById(u.id) } returns u

        val result = controller.getById(u.id)

        result.id shouldBe u.id
        result.email shouldBe u.email
    }

    "getById throws 404 when user not found" {
        val id = UUID.randomUUID()
        every { userService.findById(id) } returns null

        val ex = runCatching { controller.getById(id) }.exceptionOrNull()

        (ex is ResponseStatusException) shouldBe true
        (ex as ResponseStatusException).statusCode.value() shouldBe 404
    }

    // -------------------------------------------------------------------------
    // getByIds
    // -------------------------------------------------------------------------

    "getByIds returns matching resources" {
        val u1 = user(email = "a@x.com")
        val u2 = user(email = "b@x.com")
        every { userService.findByIds(listOf(u1.id, u2.id)) } returns listOf(u1, u2)

        val result = controller.getByIds(listOf(u1.id, u2.id))

        result.map { it.email } shouldBe listOf("a@x.com", "b@x.com")
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    "create delegates to service and returns the saved resource" {
        val resource = UserResource(
            id = null,
            externalId = "new@example.com",
            email = "new@example.com",
            firstname = "New",
        )
        val saved = user(email = "new@example.com", firstname = "New")
        every { userService.create(any()) } returns saved

        val result = controller.create(resource)

        result.email shouldBe "new@example.com"
        result.id shouldBe saved.id
        verify(exactly = 1) { userService.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update
    // -------------------------------------------------------------------------

    "update delegates to service with the path id enforced" {
        val id = UUID.randomUUID()
        val existing = user(id = id)
        val resource = UserResource(
            id = UUID.randomUUID(), // body id should be overridden by path id
            externalId = "alice@example.com",
            email = "alice@example.com",
            firstname = "Alice Updated",
        )
        val updated = existing.copy(firstname = "Alice Updated")
        every { userService.findById(id) } returns existing
        every { userService.update(any()) } returns updated

        val result = controller.update(id, resource)

        result.firstname shouldBe "Alice Updated"
        // The user passed to service must carry the path id, not the body id
        verify { userService.update(match { it.metadata.id == id }) }
    }

    "update throws 404 when user not found" {
        val id = UUID.randomUUID()
        every { userService.findById(id) } returns null

        val resource = UserResource(id = id, externalId = "x@x.com", email = "x@x.com")
        val ex = runCatching { controller.update(id, resource) }.exceptionOrNull()

        (ex is ResponseStatusException) shouldBe true
        (ex as ResponseStatusException).statusCode.value() shouldBe 404
    }

    // -------------------------------------------------------------------------
    // delete
    // -------------------------------------------------------------------------

    "delete delegates to service and succeeds when found" {
        val id = UUID.randomUUID()
        every { userService.delete(id) } returns true

        // Should not throw
        controller.delete(id)

        verify(exactly = 1) { userService.delete(id) }
    }

    "delete throws 404 when service returns false" {
        val id = UUID.randomUUID()
        every { userService.delete(id) } returns false

        val ex = runCatching { controller.delete(id) }.exceptionOrNull()

        (ex is ResponseStatusException) shouldBe true
        (ex as ResponseStatusException).statusCode.value() shouldBe 404
    }

    // -------------------------------------------------------------------------
    // getMe
    // -------------------------------------------------------------------------

    "getMe delegates to securityService and returns the resolved user as resource" {
        val u = user(email = "me@example.com")
        every { securityService.resolveCurrentUser() } returns u

        val result = controller.getMe()

        result.email shouldBe "me@example.com"
        result.id shouldBe u.id
        verify(exactly = 1) { securityService.resolveCurrentUser() }
    }
})
