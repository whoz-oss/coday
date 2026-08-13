package io.whozoss.agentos.a2a

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.a2a.dto.A2AMessage
import io.whozoss.agentos.a2a.dto.A2APart
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [A2AService] task ownership and access control.
 *
 * Regression cover for the bug where A2A-created cases were invisible in the AgentOS
 * drawer: `createCase` persisted the case but never created the direct user↔case
 * `[:ADMIN]` edge that `GET /api/cases/by-parentId/{ns}/mine` resolves through, unlike
 * `CaseController.create` and `CaseServiceImpl.startSubCase`.
 *
 * Also covers the companion hardening: `tasks/get`, `tasks/cancel` and follow-up sends
 * are checked against the caller instead of being open to anyone knowing a task UUID.
 */
class A2AServiceSpec :
    StringSpec({

        val agentConfigService = mockk<AgentConfigService>()
        val caseService = mockk<CaseService>()
        val namespaceService = mockk<NamespaceService>()
        val caseEventService = mockk<CaseEventService>()
        val userService = mockk<UserService>()
        val permissionService = mockk<PermissionService>()

        val service =
            A2AService(
                agentConfigService,
                caseService,
                namespaceService,
                caseEventService,
                userService,
                permissionService,
            )

        val namespaceId = UUID.randomUUID()
        val callerId = UUID.randomUUID()
        val caller =
            User(
                metadata = EntityMetadata(id = callerId),
                externalId = "vincent",
                email = "vincent@example.com",
            )

        val config =
            AgentConfig(
                namespaceId = namespaceId,
                name = "Sway",
                description = "test agent",
                enabled = true,
            )

        fun caseEntity(
            id: UUID = UUID.randomUUID(),
            status: CaseStatus = CaseStatus.PENDING,
        ) = Case(
            metadata = EntityMetadata(id = id),
            namespaceId = namespaceId,
            status = status,
            title = "a task",
        )

        fun userMessage(text: String = "hello", taskId: String? = null) =
            A2AMessage(
                role = "user",
                parts = listOf(A2APart.TextPart(text = text)),
                messageId = UUID.randomUUID().toString(),
                taskId = taskId,
            )

        beforeTest {
            clearAllMocks()
            every { userService.getCurrentUser() } returns caller
        }

        // ---------------------------------------------------------------
        // createCase — ownership grant
        // ---------------------------------------------------------------

        "sendMessage on a new task grants the caller ADMIN on the created case" {
            val created = caseEntity()
            every { caseService.create(any()) } returns created
            every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs
            every { caseService.addMessage(any(), any(), any(), any(), any()) } just Runs

            val task = service.sendMessage(namespaceId, config, userMessage())

            task.id shouldBe created.id.toString()
            verify(exactly = 1) {
                permissionService.grantPermission(
                    callerId.toString(),
                    EntityType.CASE,
                    created.id.toString(),
                    PermissionRelation.ADMIN,
                )
            }
        }

        "the granted user is the message actor, so the case is listed by /mine for that user" {
            val created = caseEntity()
            every { caseService.create(any()) } returns created
            every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs
            val actorSlot = slot<Actor>()
            every {
                caseService.addMessage(any(), capture(actorSlot), any(), any(), any())
            } just Runs

            service.sendMessage(namespaceId, config, userMessage())

            actorSlot.captured.id shouldBe callerId.toString()
        }

        "the first message is prefixed with the agent mention on a fresh case" {
            val created = caseEntity(status = CaseStatus.PENDING)
            every { caseService.create(any()) } returns created
            every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs
            val contentSlot = slot<List<MessageContent>>()
            every {
                caseService.addMessage(any(), any(), capture(contentSlot), any(), any())
            } just Runs

            service.sendMessage(namespaceId, config, userMessage("do the thing"))

            (contentSlot.captured.single() as MessageContent.Text).content shouldBe "@Sway do the thing"
        }

        "a failed grant kills the orphaned case and fails the call" {
            val created = caseEntity()
            every { caseService.create(any()) } returns created
            every {
                permissionService.grantPermission(any(), any(), any(), any())
            } throws RuntimeException("neo4j down")
            every { caseService.killCase(created.id) } just Runs

            val e =
                shouldThrow<A2ATaskProvisioningException> {
                    service.sendMessage(namespaceId, config, userMessage())
                }
            e.message!! shouldContain created.id.toString()

            verify(exactly = 1) { caseService.killCase(created.id) }
            verify(exactly = 0) { caseService.addMessage(any(), any(), any(), any(), any()) }
        }

        "a failed grant followed by a failed kill still fails the call" {
            val created = caseEntity()
            every { caseService.create(any()) } returns created
            every {
                permissionService.grantPermission(any(), any(), any(), any())
            } throws RuntimeException("neo4j down")
            every { caseService.killCase(created.id) } throws RuntimeException("also down")

            shouldThrow<A2ATaskProvisioningException> {
                service.sendMessage(namespaceId, config, userMessage())
            }
        }

        // ---------------------------------------------------------------
        // Access control on existing tasks
        // ---------------------------------------------------------------

        "getTask returns the snapshot when the caller has READ" {
            val existing = caseEntity()
            every { caseService.findById(existing.id) } returns existing
            every {
                permissionService.hasPermission(callerId.toString(), EntityType.CASE, existing.id.toString(), Action.READ)
            } returns true

            service.getTask(existing.id).id shouldBe existing.id.toString()
        }

        "getTask hides a task the caller cannot READ behind a not-found" {
            val other = caseEntity()
            every { caseService.findById(other.id) } returns other
            every {
                permissionService.hasPermission(any(), EntityType.CASE, other.id.toString(), Action.READ)
            } returns false

            shouldThrow<ResourceNotFoundException> { service.getTask(other.id) }
        }

        "cancelTask requires DELETE and is refused for a foreign task" {
            val other = caseEntity(status = CaseStatus.RUNNING)
            every { caseService.findById(other.id) } returns other
            every {
                permissionService.hasPermission(any(), EntityType.CASE, other.id.toString(), Action.DELETE)
            } returns false

            shouldThrow<ResourceNotFoundException> { service.cancelTask(other.id) }
            verify(exactly = 0) { caseService.killCase(any()) }
        }

        "cancelTask kills the case when the caller has DELETE" {
            val own = caseEntity(status = CaseStatus.RUNNING)
            every { caseService.findById(own.id) } returns own
            every {
                permissionService.hasPermission(any(), EntityType.CASE, own.id.toString(), Action.DELETE)
            } returns true
            every { caseService.killCase(own.id) } just Runs

            service.cancelTask(own.id)

            verify(exactly = 1) { caseService.killCase(own.id) }
        }

        "a follow-up send on a foreign task is refused before any message is added" {
            val other = caseEntity(status = CaseStatus.IDLE)
            every { caseService.findById(other.id) } returns other
            every {
                permissionService.hasPermission(any(), EntityType.CASE, other.id.toString(), Action.WRITE)
            } returns false

            shouldThrow<ResourceNotFoundException> {
                service.sendMessage(namespaceId, config, userMessage("hi", taskId = other.id.toString()))
            }
            verify(exactly = 0) { caseService.addMessage(any(), any(), any(), any(), any()) }
        }

        "a follow-up send on an owned task goes through without re-prefixing the agent mention" {
            val own = caseEntity(status = CaseStatus.IDLE)
            every { caseService.findById(own.id) } returns own
            every {
                permissionService.hasPermission(any(), EntityType.CASE, own.id.toString(), Action.WRITE)
            } returns true
            val contentSlot = slot<List<MessageContent>>()
            every { caseService.addMessage(any(), any(), capture(contentSlot), any(), any()) } just Runs

            service.sendMessage(namespaceId, config, userMessage("and then?", taskId = own.id.toString()))

            (contentSlot.captured.single() as MessageContent.Text).content shouldBe "and then?"
        }

        "requireCase enforces READ for the REST binding" {
            val other = caseEntity()
            every { caseService.findById(other.id) } returns other
            every {
                permissionService.hasPermission(any(), EntityType.CASE, other.id.toString(), Action.READ)
            } returns false

            shouldThrow<ResourceNotFoundException> { service.requireCase(other.id) }
        }

        "getOrCreateCase grants ADMIN on a fresh case and checks WRITE on an existing one" {
            val created = caseEntity()
            every { caseService.create(any()) } returns created
            every { permissionService.grantPermission(any(), any(), any(), any()) } just Runs

            val (fresh, isNew) = service.getOrCreateCase(namespaceId, taskId = null, seedTitle = "seed")
            isNew shouldBe true
            fresh.id shouldBe created.id
            verify(exactly = 1) {
                permissionService.grantPermission(
                    callerId.toString(),
                    EntityType.CASE,
                    created.id.toString(),
                    PermissionRelation.ADMIN,
                )
            }

            val existing = caseEntity()
            every { caseService.findById(existing.id) } returns existing
            every {
                permissionService.hasPermission(any(), EntityType.CASE, existing.id.toString(), Action.WRITE)
            } returns true

            val (reused, isNewAgain) =
                service.getOrCreateCase(namespaceId, taskId = existing.id.toString(), seedTitle = "seed")
            isNewAgain shouldBe false
            reused.id shouldBe existing.id
        }

        "an unknown task id is not-found before any permission lookup" {
            val unknown = UUID.randomUUID()
            every { caseService.findById(unknown) } returns null

            shouldThrow<ResourceNotFoundException> { service.getTask(unknown) }
            verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
        }
    })
