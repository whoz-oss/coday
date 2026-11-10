package io.whozoss.agentos.casePlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import io.whozoss.agentos.workflow.WorkflowTarget
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Unit tests for [BatchCaseStarterTool]: input guards, target resolution by external id, entity
 * context, dispatchKey derivation and the classification of each target's outcome.
 */
class BatchCaseStarterToolUnitSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()
        val callerId = UUID.randomUUID()
        val loopCaseId = UUID.randomUUID()
        val runId = UUID.randomUUID()

        class Fixture(
            cap: Int = 500,
            allowedAgents: List<String> = listOf("*"),
        ) {
            val users = mutableMapOf<String, User>()
            val starter = mockk<CaseStarterService>()
            val userService =
                mockk<UserService>().also { svc -> every { svc.findByExternalId(any()) } answers { users[firstArg()] } }
            val tool =
                BatchCaseStarterTool(
                    configName = null,
                    caseStarterService = starter,
                    userService = userService,
                    limitsConfig = LimitsConfigProperties(agentLoopMaxItems = cap),
                    allowedAgents = allowedAgents,
                )

            fun user(externalId: String): User =
                User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = externalId, email = externalId).also {
                    users[externalId] = it
                }

            fun startsFine() {
                every { starter.start(any()) } answers {
                    val req = firstArg<CaseStarterRequest>()
                    StartedCase(caseId = UUID.randomUUID(), userId = req.targetUserId!!, agentName = req.agentName)
                }
            }
        }

        val context =
            ToolContext(
                namespaceId = namespaceId,
                userId = callerId,
                userExternalId = "caller",
                caseEvents = listOf(WarnEvent(namespaceId = namespaceId, caseId = loopCaseId, message = "loop")),
            )

        fun input(
            vararg targets: WorkflowTarget,
            agent: String = "ProfileCaretaker",
        ) = BatchCaseStarterTool.Input(
            agentName = agent,
            targets = targets.toList(),
            promptId = UUID.randomUUID().toString(),
            runId = runId.toString(),
        )

        "starts one case per target, as the loop case's caller, for the resolved user" {
            val f = Fixture().apply { startsFine() }
            val alice = f.user("alice")
            val requests = mutableListOf<CaseStarterRequest>()
            every { f.starter.start(capture(requests)) } answers {
                StartedCase(UUID.randomUUID(), firstArg<CaseStarterRequest>().targetUserId!!, "ProfileCaretaker")
            }

            val result = f.tool.execute(input(WorkflowTarget("alice"), WorkflowTarget("alice")), context)

            result.success shouldBe true
            result.structuredOutput!!["started"].asInt() shouldBe 2
            requests.map { it.targetUserId } shouldBe listOf(alice.id, alice.id)
            requests.first().callerUserId shouldBe callerId
            requests.first().callerCaseId shouldBe loopCaseId
        }

        "a target with an entity gets an activeContext and a per-entity dispatchKey" {
            val f = Fixture().apply { startsFine() }
            val bob = f.user("bob")
            val request = slot<CaseStarterRequest>()
            every { f.starter.start(capture(request)) } answers { StartedCase(UUID.randomUUID(), bob.id, "ProfileCaretaker") }

            f.tool.execute(input(WorkflowTarget("bob", entityId = "t-1", entityType = "TALENT")), context)

            request.captured.dispatchKey shouldBe DispatchKey.of(runId, bob.id, "t-1")
            request.captured.sessionContext shouldBe
                mapOf("activeContext" to listOf(mapOf("type" to "TALENT", "data" to mapOf("id" to "t-1"))))
        }

        "a target without an entity gets no session context and a per-user dispatchKey" {
            val f = Fixture().apply { startsFine() }
            val carol = f.user("carol")
            val request = slot<CaseStarterRequest>()
            every { f.starter.start(capture(request)) } answers { StartedCase(UUID.randomUUID(), carol.id, "ProfileCaretaker") }

            f.tool.execute(input(WorkflowTarget("carol")), context)

            request.captured.dispatchKey shouldBe DispatchKey.of(runId, carol.id)
            request.captured.sessionContext shouldBe null
        }

        "each target outcome is counted: unknown user, already dispatched, no access, failure" {
            val f = Fixture()
            val dup = f.user("dup")
            val denied = f.user("denied")
            val broken = f.user("broken")
            every { f.starter.start(match { it.targetUserId == dup.id }) } throws
                CaseStarterRefusedException(CaseStarterRefusal.ALREADY_DISPATCHED, "dup")
            every { f.starter.start(match { it.targetUserId == denied.id }) } throws
                CaseStarterRefusedException(CaseStarterRefusal.AGENT_NOT_AVAILABLE, "no")
            every { f.starter.start(match { it.targetUserId == broken.id }) } throws IllegalStateException("boom")

            val out =
                f.tool
                    .execute(
                        input(WorkflowTarget("ghost"), WorkflowTarget("dup"), WorkflowTarget("denied"), WorkflowTarget("broken")),
                        context,
                    ).structuredOutput!!

            out["started"].asInt() shouldBe 0
            out["unknownUser"].asInt() shouldBe 1
            out["skipped"].asInt() shouldBe 1
            out["noAgentAccess"].asInt() shouldBe 1
            out["failed"].asInt() shouldBe 1
        }

        "more targets than the cap fails and starts nothing" {
            val f = Fixture(cap = 1)
            f.user("a")
            f.user("b")

            val result = f.tool.execute(input(WorkflowTarget("a"), WorkflowTarget("b")), context)

            result.errorType shouldBe "OVER_BATCH_CAP"
            verify(exactly = 0) { f.starter.start(any()) }
        }

        "input guards: missing targets, missing prompt, invalid ids, disallowed agent" {
            val f = Fixture(allowedAgents = listOf("Profile*"))

            f.tool.execute(input(), context).errorType shouldBe "INVALID_INPUT"
            f.tool.execute(input(WorkflowTarget("a")).copy(promptId = null), context).errorType shouldBe "MISSING_INPUT"
            f.tool.execute(input(WorkflowTarget("a")).copy(runId = "nope"), context).let {
                it.errorType shouldBe "INVALID_INPUT"
                it.output shouldContain "runId"
            }
            f.tool.execute(input(WorkflowTarget("a"), agent = "Reporter"), context).errorType shouldBe "UNAUTHORIZED_AGENT"
            verify(exactly = 0) { f.starter.start(any()) }
        }

        "cancellation is not swallowed" {
            val f = Fixture()
            f.user("a")
            every { f.starter.start(any()) } throws CancellationException("stop")

            io.kotest.assertions.throwables.shouldThrow<CancellationException> {
                f.tool.execute(input(WorkflowTarget("a")), context)
            }
        }
    })
