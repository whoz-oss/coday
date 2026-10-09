package io.whozoss.agentos.workflow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

/**
 * Unit tests for [CaseLauncherServiceImpl].
 *
 * Each test wires [CaseLauncherServiceImpl] directly with mocked collaborators — [CaseService],
 * [PermissionService] and [UserService] — and verifies the contract described by [CaseLauncherService].
 */
class CaseLauncherServiceImplSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()
        val agentName = "test-agent"

        val activeUser =
            User(
                metadata = EntityMetadata(id = userId),
                externalId = "ext-1",
                email = "test@example.com",
            )

        fun buildLauncher(
            caseService: CaseService = mockk(relaxed = true),
            permissionService: PermissionService = mockk(relaxed = true),
            userService: UserService =
                mockk {
                    every { getById(userId) } returns activeUser
                },
        ) = CaseLauncherServiceImpl(
            caseService = caseService,
            permissionService = permissionService,
            userService = userService,
        )

        // -------------------------------------------------------------------------
        // Happy path
        // -------------------------------------------------------------------------

        "launchCase creates a standalone case and routes the task to the agent via @mention" {
            val createdCase = Case(namespaceId = namespaceId, title = "do something")

            val caseService =
                mockk<CaseService> {
                    every { create(any()) } returns createdCase
                    every { addMessage(any(), any(), any(), any(), any()) } returns Unit
                }
            val permissionService = mockk<PermissionService>(relaxed = true)
            val userService = mockk<UserService> { every { getById(userId) } returns activeUser }

            val launcher =
                buildLauncher(
                    caseService = caseService,
                    permissionService = permissionService,
                    userService = userService,
                )

            val launchedId =
                launcher.launchCase(
                    namespaceId = namespaceId,
                    agentName = agentName,
                    task = "do something",
                    onBehalfOfUserId = userId,
                    sessionContext = null,
                )

            launchedId shouldBe createdCase.id

            // Case must be created without a parent link and with the task as title
            verify {
                caseService.create(
                    match { it.namespaceId == namespaceId && it.parentCaseId == null && it.title == "do something" },
                )
            }

            // ADMIN permission must be granted to the requesting user
            verify {
                permissionService.grantPermission(
                    userId.toString(),
                    EntityType.CASE,
                    createdCase.id.toString(),
                    PermissionRelation.ADMIN,
                )
            }

            // The first message must be a @mention routing to the agent, sent as the user actor
            verify {
                caseService.addMessage(
                    caseId = createdCase.id,
                    actor = match { it.id == userId.toString() && it.role == ActorRole.USER },
                    content = listOf(MessageContent.Text("@$agentName do something")),
                    sessionContext = null,
                )
            }
        }

        "launchCase title is capped at 80 characters" {
            val longTask = "x".repeat(120)
            val caseService =
                mockk<CaseService> {
                    every { create(any()) } answers { firstArg<Case>() }
                    every { addMessage(any(), any(), any(), any(), any()) } returns Unit
                }

            buildLauncher(caseService = caseService).launchCase(
                namespaceId = namespaceId,
                agentName = agentName,
                task = longTask,
                onBehalfOfUserId = userId,
                sessionContext = null,
            )

            verify {
                caseService.create(match { it.title == longTask.take(80) })
            }
        }

        "launchCase forwards sessionContext to addMessage" {
            val sessionCtx = mapOf("lang" to "fr", "tenantId" to "corp-42")
            val createdCase = Case(namespaceId = namespaceId)
            val caseService =
                mockk<CaseService> {
                    every { create(any()) } returns createdCase
                    every { addMessage(any(), any(), any(), any(), any()) } returns Unit
                }

            buildLauncher(caseService = caseService).launchCase(
                namespaceId = namespaceId,
                agentName = agentName,
                task = "task",
                onBehalfOfUserId = userId,
                sessionContext = sessionCtx,
            )

            verify {
                caseService.addMessage(
                    caseId = createdCase.id,
                    actor = any(),
                    content = any(),
                    sessionContext = sessionCtx,
                )
            }
        }

        // -------------------------------------------------------------------------
        // Permission-grant failure: orphaned case must be deleted
        // -------------------------------------------------------------------------

        "launchCase deletes the orphaned case and throws when the permission grant fails" {
            val createdCase = Case(namespaceId = namespaceId)
            val caseService =
                mockk<CaseService> {
                    every { create(any()) } returns createdCase
                    every { delete(any()) } returns true
                }
            val permissionService =
                mockk<PermissionService> {
                    every { grantPermission(any(), any(), any(), any()) } throws RuntimeException("Neo4j unavailable")
                }

            val launcher = buildLauncher(caseService = caseService, permissionService = permissionService)

            shouldThrow<AccessDeniedException> {
                launcher.launchCase(
                    namespaceId = namespaceId,
                    agentName = agentName,
                    task = "task",
                    onBehalfOfUserId = userId,
                    sessionContext = null,
                )
            }

            // The orphaned case must have been deleted
            verify { caseService.delete(createdCase.id) }
            // addMessage must NOT have been called — the case was already killed
            verify(exactly = 0) { caseService.addMessage(any(), any(), any(), any(), any()) }
        }

        "launchCase still throws even when the orphan delete also fails" {
            val createdCase = Case(namespaceId = namespaceId)
            val caseService =
                mockk<CaseService> {
                    every { create(any()) } returns createdCase
                    every { delete(any()) } throws RuntimeException("delete also failed")
                }
            val permissionService =
                mockk<PermissionService> {
                    every { grantPermission(any(), any(), any(), any()) } throws RuntimeException("grant failed")
                }

            shouldThrow<AccessDeniedException> {
                buildLauncher(caseService = caseService, permissionService = permissionService).launchCase(
                    namespaceId = namespaceId,
                    agentName = agentName,
                    task = "task",
                    onBehalfOfUserId = userId,
                    sessionContext = null,
                )
            }
        }
    })
