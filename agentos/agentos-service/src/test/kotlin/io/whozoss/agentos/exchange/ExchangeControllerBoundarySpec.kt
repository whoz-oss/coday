package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.exchange.ExchangeCapability
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** The Exchange controller against a resolver that is not the default one, without any Git dependency. */
class ExchangeControllerBoundarySpec : StringSpec({
    class Fixture {
        val case = Case(namespaceId = UUID.randomUUID())
        val user = User(externalId = "exchange-user", email = "exchange@example.com")
        val storage = mockk<ExchangeStorageService>(relaxed = true)
        val permissions = mockk<PermissionService> {
            every { hasPermission(user.id.toString(), EntityType.CASE, case.id.toString(), any()) } returns true
        }
        var root = ResolvedExchangeRoot(Path.of("/provider/documents"), case.id)
        var onMutation: () -> Unit = {}
        val resolver = object : ExchangeRootResolver {
            override fun resolve(case: Case) = root
            override fun resolve(caseId: UUID, namespaceId: UUID, caseCreatedAt: Instant) = root
            override fun <T> withFileMutation(case: Case, action: (ResolvedExchangeRoot) -> T): T {
                onMutation()
                return action(root)
            }
        }
        val cases = mockk<CaseService> { every { findById(case.id) } returns case }
        val controller = ExchangeController(
            storage,
            cases,
            ExchangeCapabilityService(permissions),
            mockk<UserService> { every { getCurrentUser() } returns user },
            resolver,
        )
    }

    "reads and mutations use the directory supplied by the resolver" {
        val f = Fixture()

        f.controller.getCaseFilesManifest(f.case.id)
        f.controller.deleteCaseFile(f.case.id, "report.txt")

        verify { f.storage.listManifest(f.root.path, ExchangeScope.CASE) }
        verify { f.storage.delete(f.root.path, "report.txt") }
    }

    "a directory owned by another case never grants that owner's permissions" {
        val f = Fixture()
        val ownerId = UUID.randomUUID()
        f.root = f.root.copy(ownerCaseId = ownerId)
        every { f.permissions.hasPermission(f.user.id.toString(), EntityType.CASE, ownerId.toString(), any()) } returns false

        shouldThrow<AccessDeniedException> { f.controller.getCaseFilesManifest(f.case.id) }
        shouldThrow<AccessDeniedException> { f.controller.deleteCaseFile(f.case.id, "report.txt") }

        verify(exactly = 0) { f.storage.listManifest(any(), any()) }
        verify(exactly = 0) { f.storage.delete(any(), any()) }
    }

    "the capability over a shared directory is capped by the owner's permission" {
        val f = Fixture()
        val ownerId = UUID.randomUUID()
        f.root = f.root.copy(ownerCaseId = ownerId)
        every { f.permissions.hasPermission(f.user.id.toString(), EntityType.CASE, ownerId.toString(), io.whozoss.agentos.permissions.Action.READ) } returns true
        every { f.permissions.hasPermission(f.user.id.toString(), EntityType.CASE, ownerId.toString(), io.whozoss.agentos.permissions.Action.WRITE) } returns false

        f.controller.getCaseFilesManifest(f.case.id).capability shouldBe ExchangeCapability.READ
    }

    "an environment that becomes unavailable cannot write to a fallback directory" {
        val f = Fixture()
        f.onMutation = { f.root = f.root.copy(unavailableReason = "Environment is being removed") }

        shouldThrow<ConflictException> { f.controller.deleteCaseFile(f.case.id, "report.txt") }

        verify(exactly = 0) { f.storage.delete(any(), any()) }
    }
})
