package io.whozoss.agentos.casePlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [CaseLaunchService]: the case it creates (parent link, title, first message, ADMIN
 * grant, context), the target user and the ADMIN rule, agent access, and cleanup on failure. A
 * refusal must never create a case.
 */
class CaseLaunchServiceUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val callerCaseId: UUID = UUID.randomUUID()

        val caller = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "caller", email = "caller@example.com")
        val other = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "other", email = "other@example.com")

        /** A message sent to a case, as `CaseService.addMessage` received it. */
        data class Sent(
            val caseId: UUID,
            val actor: Actor,
            val content: List<MessageContent>,
            val sessionContext: Map<String, Any?>?,
        )

        class Fixture {
            val created = mutableListOf<Case>()
            val sent = mutableListOf<Sent>()
            val deleted = mutableListOf<UUID>()
            var createFailure: Throwable? = null
            var grantFailure: Throwable? = null
            var sendFailure: Throwable? = null
            var deleteFailure: Throwable? = null

            val userService = mockk<UserService>()
            val agentConfigService = mockk<AgentConfigService>()
            val permissionService = mockk<PermissionService>()
            val caseService =
                mockk<CaseService> {
                    every { create(any()) } answers {
                        createFailure?.let { throw it }
                        firstArg<Case>().also { created += it }
                    }
                    every { addMessage(any(), any(), any(), any(), any()) } answers {
                        sendFailure?.let { throw it }
                        sent += Sent(arg(0), arg(1), arg(2), arg(4))
                    }
                    every { delete(any()) } answers {
                        deleteFailure?.let { throw it }
                        deleted += firstArg<UUID>()
                        true
                    }
                }
            val service = CaseLaunchService(caseService, userService, agentConfigService, permissionService)

            init {
                every { userService.findById(caller.id) } returns caller
                every { userService.findById(other.id) } returns other
                every { permissionService.grantPermission(any(), any(), any(), any()) } answers {
                    grantFailure?.let { throw it }
                    Unit
                }
                // Default world: both users read the namespace, nobody administers it, both have 'Reporter'.
                namespacePermission(caller, Action.READ, true)
                namespacePermission(caller, Action.WRITE, false)
                namespacePermission(other, Action.READ, true)
                namespacePermission(other, Action.WRITE, false)
                deployAgent(caller, "Reporter")
                deployAgent(other, "Reporter")
            }

            fun namespacePermission(
                user: User,
                action: Action,
                allowed: Boolean,
            ) {
                every {
                    permissionService.hasPermission(user.id.toString(), EntityType.NAMESPACE, namespaceId.toString(), action)
                } returns allowed
            }

            fun deployAgent(
                user: User,
                vararg names: String,
            ) {
                every {
                    agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, user.id, any())
                } returns names.map { AgentConfig(namespaceId = namespaceId, name = it) }
            }
        }

        fun request(
            agentName: String = "Reporter",
            task: String = "Summarise the week",
            targetUserId: UUID? = null,
            title: String? = null,
            context: Map<String, Any?>? = null,
        ) = CaseLaunchRequest(caller.id, namespaceId, callerCaseId, agentName, task, targetUserId, title, context)

        fun CaseLaunchOutcome.refusal() = shouldBeInstanceOf<CaseLaunchOutcome.Refused>()

        // -------------------------------------------------------------------------
        // The case it creates
        // -------------------------------------------------------------------------

        "creates the case in the calling case's namespace, with the calling case as its origin, and returns its id" {
            val f = Fixture()

            val outcome = f.service.launch(request()).shouldBeInstanceOf<CaseLaunchOutcome.Launched>()

            val case = f.created.single()
            outcome.caseId shouldBe case.id
            outcome.namespaceId shouldBe namespaceId
            outcome.userId shouldBe caller.id
            case.originCaseId shouldBe callerCaseId
            case.namespaceId shouldBe namespaceId
        }

        "the new case is a top-level case, not a sub-case of the calling one" {
            val f = Fixture()

            f.service.launch(request())

            f.created.single().parentCaseId shouldBe null
        }

        "the first message is the prompt addressed to the agent, sent as the user the case runs for" {
            val f = Fixture()

            f.service.launch(request(task = "Summarise the week"))

            f.sent.single().also {
                it.caseId shouldBe f.created.single().id
                it.actor.id shouldBe caller.id.toString()
                it.actor.role shouldBe ActorRole.USER
                it.content shouldBe listOf(MessageContent.Text("@Reporter Summarise the week"))
            }
        }

        "the user the case runs for is given ADMIN on it" {
            val f = Fixture()

            f.service.launch(request())

            verify(exactly = 1) {
                f.permissionService.grantPermission(
                    caller.id.toString(),
                    EntityType.CASE,
                    f.created.single().id.toString(),
                    PermissionRelation.ADMIN,
                )
            }
        }

        "uses the agent's real casing, not the one the LLM typed" {
            val f = Fixture()

            f.service.launch(request(agentName = "reporter"))

            f.sent.single().content shouldBe listOf(MessageContent.Text("@Reporter Summarise the week"))
        }

        // -------------------------------------------------------------------------
        // Title
        // -------------------------------------------------------------------------

        "the title is the given one" {
            val f = Fixture()

            f.service.launch(request(title = "Weekly review"))

            f.created.single().title shouldBe "Weekly review"
        }

        "without a title, or with a blank one, it is derived from the prompt" {
            val f = Fixture()

            f.service.launch(request(task = "Summarise the week"))
            f.service.launch(request(task = "Summarise the week", title = "   "))

            f.created.map { it.title } shouldBe listOf("Summarise the week", "Summarise the week")
        }

        "the title is capped at 80 characters, given or derived" {
            val f = Fixture()

            f.service.launch(request(title = "t".repeat(200)))
            f.service.launch(request(task = "x".repeat(200)))

            f.created.map { it.title } shouldBe listOf("t".repeat(80), "x".repeat(80))
        }

        // -------------------------------------------------------------------------
        // Context
        // -------------------------------------------------------------------------

        "without a context the first message carries no session context" {
            val f = Fixture()

            f.service.launch(request())

            f.sent.single().sessionContext shouldBe null
        }

        "an empty context counts as none" {
            val f = Fixture()

            f.service.launch(request(context = emptyMap()))

            f.sent.single().sessionContext shouldBe null
        }

        "the context handed over is the session context of the first message, exactly as given" {
            val f = Fixture()
            val context = mapOf("activeContext" to listOf(mapOf("type" to "TALENT", "data" to mapOf("id" to "t42"))))

            f.service.launch(request(context = context))

            f.sent.single().sessionContext shouldBe context
        }

        // -------------------------------------------------------------------------
        // Agent access and calling user
        // -------------------------------------------------------------------------

        "refuses an agent that is not deployed to the user, including a prefix-only match" {
            val f = Fixture()
            f.deployAgent(caller, "Reporter-v2")

            f.service.launch(request()).refusal().reason shouldBe CaseLaunchRefusal.AGENT_NOT_AVAILABLE
            f.created shouldBe emptyList()
        }

        "a calling user that no longer exists is refused" {
            val f = Fixture()
            every { f.userService.findById(caller.id) } returns null

            f.service.launch(request()).refusal().reason shouldBe CaseLaunchRefusal.CALLER_NOT_FOUND
            f.created shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // Another user: ADMIN only
        // -------------------------------------------------------------------------

        "a plain member cannot start a case for another user, and no case is created" {
            val f = Fixture()

            f.service.launch(request(targetUserId = other.id)).refusal().reason shouldBe CaseLaunchRefusal.FORBIDDEN
            f.created shouldBe emptyList()
        }

        "a namespace administrator can start a case for another user, who is the one given ADMIN on it" {
            val f = Fixture()
            f.namespacePermission(caller, Action.WRITE, true)

            val outcome = f.service.launch(request(targetUserId = other.id)).shouldBeInstanceOf<CaseLaunchOutcome.Launched>()

            outcome.userId shouldBe other.id
            f.sent.single().actor.id shouldBe other.id.toString()
            // The origin is still the calling case, whoever the new case runs for.
            f.created.single().originCaseId shouldBe callerCaseId
            verify(exactly = 1) {
                f.permissionService.grantPermission(
                    other.id.toString(),
                    EntityType.CASE,
                    f.created.single().id.toString(),
                    PermissionRelation.ADMIN,
                )
            }
            verify(exactly = 0) {
                f.permissionService.grantPermission(caller.id.toString(), any(), any(), any())
            }
        }

        "the target user must still read the namespace" {
            val f = Fixture()
            f.namespacePermission(caller, Action.WRITE, true)
            f.namespacePermission(other, Action.READ, false)

            f.service.launch(request(targetUserId = other.id)).refusal().reason shouldBe CaseLaunchRefusal.NO_ACCESS
            f.created shouldBe emptyList()
        }

        "the target user must still have the agent deployed to them" {
            val f = Fixture()
            f.namespacePermission(caller, Action.WRITE, true)
            f.deployAgent(other, "SomethingElse")

            f.service.launch(request(targetUserId = other.id)).refusal().reason shouldBe CaseLaunchRefusal.AGENT_NOT_AVAILABLE
            f.created shouldBe emptyList()
        }

        "an unknown target user is refused" {
            val f = Fixture()
            f.namespacePermission(caller, Action.WRITE, true)
            val ghost = UUID.randomUUID()
            every { f.userService.findById(ghost) } returns null

            f.service.launch(request(targetUserId = ghost)).refusal().reason shouldBe CaseLaunchRefusal.USER_NOT_FOUND
            f.created shouldBe emptyList()
        }

        "naming the caller itself is not 'another user' and asks for no administration right" {
            val f = Fixture()

            f.service.launch(request(targetUserId = caller.id)).shouldBeInstanceOf<CaseLaunchOutcome.Launched>()

            f.sent.single().actor.id shouldBe caller.id.toString()
            verify(exactly = 0) {
                f.permissionService.hasPermission(caller.id.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
            }
        }

        // -------------------------------------------------------------------------
        // Failures
        // -------------------------------------------------------------------------

        "any other failure to create the case is reported without leaking its message, and nothing else happens" {
            val f = Fixture()
            f.createFailure = IllegalStateException("boom")

            val refusal = f.service.launch(request()).refusal()

            refusal.reason shouldBe CaseLaunchRefusal.LAUNCH_FAILED
            refusal.message shouldNotContain "boom"
            f.sent shouldBe emptyList()
            verify(exactly = 0) { f.permissionService.grantPermission(any(), any(), any(), any()) }
        }

        "when the ADMIN grant fails the case is deleted and no message is sent" {
            val f = Fixture()
            f.grantFailure = RuntimeException("Neo4j unavailable")

            val refusal = f.service.launch(request()).refusal()

            refusal.reason shouldBe CaseLaunchRefusal.LAUNCH_FAILED
            refusal.message shouldNotContain "Neo4j"
            f.deleted shouldBe listOf(f.created.single().id)
            f.sent shouldBe emptyList()
        }

        "when the first message cannot be sent the case is deleted" {
            val f = Fixture()
            f.sendFailure = IllegalStateException("boom")

            val refusal = f.service.launch(request()).refusal()

            refusal.reason shouldBe CaseLaunchRefusal.LAUNCH_FAILED
            refusal.message shouldNotContain "boom"
            f.deleted shouldBe listOf(f.created.single().id)
        }

        "it is still refused when deleting the half-started case fails too" {
            val f = Fixture()
            f.grantFailure = RuntimeException("grant failed")
            f.deleteFailure = RuntimeException("delete also failed")

            f.service.launch(request()).refusal().reason shouldBe CaseLaunchRefusal.LAUNCH_FAILED
        }
    })
