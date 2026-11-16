package io.whozoss.agentos.casePlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [CaseToolService]: the guards the read side of the `CASE` tools applies, in the order it applies
 * them. The permission comes first, like `@PreAuthorize` on the REST path, so a caller without Case READ never
 * triggers a read of the repository. Mocked collaborators, no Spring context.
 *
 * 1. anonymous call (userId null): null, nothing is consulted
 * 2. permission denied: null, the repository is never read
 * 3. case not found: null
 * 4. namespace mismatch: null
 * 5. permission granted, same namespace: the events
 */
class CaseToolServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val otherNamespaceId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()

        fun buildCase(
            id: UUID = UUID.randomUUID(),
            ns: UUID = namespaceId,
        ) = Case(
            metadata = EntityMetadata(id = id),
            namespaceId = ns,
            status = CaseStatus.IDLE,
            title = "Test case",
        )

        /** A world where [case] is the only case, and [canRead] answers the Case READ check of [userId]. */
        class Fixture(
            val case: Case? = null,
            canRead: Boolean = true,
        ) {
            val caseRepository = mockk<CaseRepository>()
            val caseEventService = mockk<CaseEventService>()
            val permissionService = mockk<PermissionService>()
            val service = CaseToolService(caseRepository, caseEventService, permissionService)

            init {
                every { permissionService.hasPermission(any(), EntityType.CASE, any(), Action.READ) } returns canRead
                every { caseRepository.findByIds(any<Collection<UUID>>()) } answers {
                    listOfNotNull(case?.takeIf { it.id in firstArg<Collection<UUID>>() })
                }
                every { caseEventService.findByParent(any()) } returns emptyList()
            }
        }

        "returns null without consulting anything when the call is anonymous" {
            val f = Fixture(case = buildCase())

            f.service.findCaseEvents(f.case!!.id, namespaceId, userId = null).shouldBeNull()

            verify(exactly = 0) { f.permissionService.hasPermission(any(), any(), any(), any()) }
            verify(exactly = 0) { f.caseRepository.findByIds(any<Collection<UUID>>()) }
        }

        "returns null and never reads the repository when the caller lacks Case READ" {
            val f = Fixture(case = buildCase(), canRead = false)

            f.service.findCaseEvents(f.case!!.id, namespaceId, userId).shouldBeNull()

            verify(exactly = 1) {
                f.permissionService.hasPermission(userId.toString(), EntityType.CASE, f.case.id.toString(), Action.READ)
            }
            verify(exactly = 0) { f.caseRepository.findByIds(any<Collection<UUID>>()) }
            verify(exactly = 0) { f.caseEventService.findByParent(any()) }
        }

        "returns null when the case does not exist, even for a caller that holds the permission" {
            val f = Fixture(case = null, canRead = true)

            f.service.findCaseEvents(UUID.randomUUID(), namespaceId, userId).shouldBeNull()

            verify(exactly = 0) { f.caseEventService.findByParent(any()) }
        }

        "returns null when the case belongs to a different namespace, after the permission was consulted" {
            val f = Fixture(case = buildCase(ns = otherNamespaceId), canRead = true)

            f.service.findCaseEvents(f.case!!.id, namespaceId, userId).shouldBeNull()

            verify(exactly = 1) {
                f.permissionService.hasPermission(userId.toString(), EntityType.CASE, f.case.id.toString(), Action.READ)
            }
            verify(exactly = 0) { f.caseEventService.findByParent(any()) }
        }

        "returns the events when the caller holds Case READ and the case is in the namespace" {
            val f = Fixture(case = buildCase(), canRead = true)

            val events = f.service.findCaseEvents(f.case!!.id, namespaceId, userId)

            events.shouldNotBeNull() shouldBe emptyList()
            verify(exactly = 1) {
                f.permissionService.hasPermission(userId.toString(), EntityType.CASE, f.case.id.toString(), Action.READ)
            }
            verify(exactly = 1) { f.caseEventService.findByParent(f.case.id) }
        }
    })
