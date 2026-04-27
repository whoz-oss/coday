package io.whozoss.agentos.bootstrap

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.boot.ApplicationArguments

class BootstrapServiceImplSpec : StringSpec({

    val userService = mockk<UserService>(relaxed = true)
    val applicationArguments = mockk<ApplicationArguments>()

    fun service(
        configured: String = "",
        jvmUser: String = "admin",
    ) = BootstrapServiceImpl(
        userService = userService,
        configuredAdminExternalId = configured,
        jvmUserName = jvmUser,
    )

    beforeTest { clearAllMocks() }

    "creates a default admin with isAdmin=true when DB is empty" {
        every { userService.count() } returns 0L
        val captured = slot<User>()
        every { userService.create(capture(captured)) } answers { firstArg() }

        service().bootstrap()

        verify(exactly = 1) { userService.create(any()) }
        verify(exactly = 0) { userService.update(any()) }
        captured.captured.externalId shouldBe "admin"
        captured.captured.isAdmin shouldBe true
        captured.captured.email shouldBe ""
    }

    "uses configured externalId for the default admin" {
        every { userService.count() } returns 0L
        val captured = slot<User>()
        every { userService.create(capture(captured)) } answers { firstArg() }

        service(configured = "selim").bootstrap()

        captured.captured.externalId shouldBe "selim"
        captured.captured.isAdmin shouldBe true
    }

    "derives email from externalId when it looks like an email" {
        every { userService.count() } returns 0L
        val captured = slot<User>()
        every { userService.create(capture(captured)) } answers { firstArg() }

        service(configured = "ops@whoz.com").bootstrap()

        captured.captured.email shouldBe "ops@whoz.com"
    }

    "promotes the single existing user when isAdmin is false (legacy migration)" {
        val legacy = User(
            metadata = EntityMetadata(),
            externalId = "selim",
            email = "",
            isAdmin = false,
        )
        every { userService.count() } returns 1L
        every { userService.findAll() } returns listOf(legacy)
        val captured = slot<User>()
        every { userService.update(capture(captured)) } answers { firstArg() }

        service().bootstrap()

        verify(exactly = 1) { userService.update(any()) }
        verify(exactly = 0) { userService.create(any()) }
        captured.captured.externalId shouldBe "selim"
        captured.captured.isAdmin shouldBe true
    }

    "is a no-op when the single user is already admin" {
        val admin = User(
            metadata = EntityMetadata(),
            externalId = "selim",
            isAdmin = true,
        )
        every { userService.count() } returns 1L
        every { userService.findAll() } returns listOf(admin)

        service().bootstrap()

        verify(exactly = 0) { userService.update(any()) }
        verify(exactly = 0) { userService.create(any()) }
    }

    "is a no-op when 2 or more users exist" {
        every { userService.count() } returns 2L

        service().bootstrap()

        verify(exactly = 0) { userService.update(any()) }
        verify(exactly = 0) { userService.create(any()) }
        verify(exactly = 0) { userService.findAll() }
    }

    "skips promotion if count==1 but findAll returns empty (race / repo inconsistency)" {
        every { userService.count() } returns 1L
        every { userService.findAll() } returns emptyList()

        service().bootstrap()

        verify(exactly = 0) { userService.update(any()) }
        verify(exactly = 0) { userService.create(any()) }
    }

    "run delegates to bootstrap" {
        every { userService.count() } returns 5L

        service().run(applicationArguments)

        verify(exactly = 1) { userService.count() }
    }

    "isBootstrapped returns true when at least one user exists" {
        every { userService.count() } returns 1L
        service().isBootstrapped() shouldBe true
    }

    "isBootstrapped returns false when DB is empty" {
        every { userService.count() } returns 0L
        service().isBootstrapped() shouldBe false
    }

    "falls back to JVM user.name when configured externalId is blank" {
        every { userService.count() } returns 0L
        val captured = slot<User>()
        every { userService.create(capture(captured)) } answers { firstArg() }

        service(configured = "", jvmUser = "selim").bootstrap()

        captured.captured.externalId shouldBe "selim"
        captured.captured.isAdmin shouldBe true
    }

    "explicit configured externalId wins over JVM user.name" {
        every { userService.count() } returns 0L
        val captured = slot<User>()
        every { userService.create(capture(captured)) } answers { firstArg() }

        service(configured = "ops", jvmUser = "selim").bootstrap()

        captured.captured.externalId shouldBe "ops"
    }

    "falls back to 'admin' sentinel when both configured and JVM user.name are blank" {
        every { userService.count() } returns 0L
        val captured = slot<User>()
        every { userService.create(capture(captured)) } answers { firstArg() }

        service(configured = "", jvmUser = "").bootstrap()

        captured.captured.externalId shouldBe "admin"
    }
})
