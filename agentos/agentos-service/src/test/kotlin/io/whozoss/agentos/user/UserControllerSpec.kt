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
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover:
 * - [UserController.listAll] — delegates to [UserService.findAll]
 * - [UserController.getMe]  — delegates to [SecurityService.resolveCurrentUser]
 * - Inherited [EntityController] endpoints: getById (found / not-found),
 *   getByIds, create, update (found / not-found), delete (found / not-found)
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

    "listAll returns all users from the service" {
        val u1 = user(email = "alice@example.com")
        val u2 = user(email = "bob@example.com")
        every { userService.findAll() } returns listOf(u1, u2)

        val result = controller.listAll()

        result shouldBe listOf(u1, u2)
        verify(exactly = 1) { userService.findAll() }
    }

    "listAll returns empty list when no users exist" {
        every { userService.findAll() } returns emptyList()

        controller.listAll() shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // getMe
    // -------------------------------------------------------------------------

    "getMe delegates to SecurityService and returns the resolved user" {
        val u = user(email = "me@example.com")
        every { securityService.resolveCurrentUser() } returns u

        val result = controller.getMe()

        result shouldBe u
        verify(exactly = 1) { securityService.resolveCurrentUser() }
    }

    // -------------------------------------------------------------------------
    // getById (inherited from EntityController)
    // -------------------------------------------------------------------------

    "getById returns the user when found" {
        val u = user()
        // EntityController.getById delegates to service.findById which is a default
        // method on EntityService calling findByIds — stub both to be safe.
        every { userService.findByIds(listOf(u.id)) } returns listOf(u)
        every { userService.findById(u.id) } returns u

        val result = controller.getById(u.id)

        result shouldBe u
    }

    "getById throws 404 when user not found" {
        val id = UUID.randomUUID()
        every { userService.findByIds(listOf(id)) } returns emptyList()
        every { userService.findById(id) } returns null

        val ex = runCatching { controller.getById(id) }.exceptionOrNull()

        (ex is ResponseStatusException) shouldBe true
        (ex as ResponseStatusException).statusCode.value() shouldBe 404
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited from EntityController)
    // -------------------------------------------------------------------------

    "getByIds returns matching users" {
        val u1 = user(email = "a@x.com")
        val u2 = user(email = "b@x.com")
        every { userService.findByIds(listOf(u1.id, u2.id)) } returns listOf(u1, u2)

        val result = controller.getByIds(listOf(u1.id, u2.id))

        result shouldBe listOf(u1, u2)
    }

    // -------------------------------------------------------------------------
    // create (inherited from EntityController)
    // -------------------------------------------------------------------------

    "create delegates to service and returns the saved user" {
        val u = user()
        every { userService.create(u) } returns u

        val result = controller.create(u)

        result shouldBe u
        verify(exactly = 1) { userService.create(u) }
    }

    // -------------------------------------------------------------------------
    // update (inherited from EntityController)
    // -------------------------------------------------------------------------

    "update delegates to service when user exists" {
        val u = user()
        val updated = u.copy(firstname = "Updated")
        every { userService.findByIds(listOf(u.id)) } returns listOf(u)
        every { userService.findById(u.id) } returns u
        every { userService.update(updated) } returns updated

        val result = controller.update(u.id, updated)

        result shouldBe updated
        verify(exactly = 1) { userService.update(updated) }
    }

    "update throws 404 when user not found" {
        val id = UUID.randomUUID()
        val u = user(id = id)
        every { userService.findByIds(listOf(id)) } returns emptyList()
        every { userService.findById(id) } returns null

        val ex = runCatching { controller.update(id, u) }.exceptionOrNull()

        (ex is ResponseStatusException) shouldBe true
        (ex as ResponseStatusException).statusCode.value() shouldBe 404
    }

    // -------------------------------------------------------------------------
    // delete (inherited from EntityController)
    // -------------------------------------------------------------------------

    "delete succeeds when user exists" {
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
})
