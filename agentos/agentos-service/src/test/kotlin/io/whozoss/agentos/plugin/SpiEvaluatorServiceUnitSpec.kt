package io.whozoss.agentos.plugin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.spi.AnswerInterceptResult
import io.whozoss.agentos.sdk.spi.AnswerInterceptor
import io.whozoss.agentos.sdk.spi.ExternalContextBindingRegistrar
import io.whozoss.agentos.sdk.spi.ToolGrantDecision
import io.whozoss.agentos.sdk.spi.ToolGrantPolicy
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.UUID

class SpiEvaluatorServiceUnitSpec : StringSpec({

    val service = SpiEvaluatorService()

    // -------------------------------------------------------------------------
    // AnswerInterceptor evaluation
    // -------------------------------------------------------------------------

    val caseId = UUID.randomUUID()
    val question =
        QuestionEvent(
            namespaceId = UUID.randomUUID(),
            caseId = caseId,
            agentId = UUID.randomUUID(),
            agentName = "Agent",
            question = "Proceed?",
        )
    val actor = Actor(id = UUID.randomUUID().toString(), displayName = "User", role = ActorRole.USER)

    fun interceptor(decide: () -> AnswerInterceptResult) =
        object : AnswerInterceptor {
            override suspend fun interceptAnswer(
                caseId: UUID,
                questionEvent: QuestionEvent,
                answerText: String,
                actor: Actor,
            ) = decide()
        }

    "no interceptor registered accepts the answer" {
        service.evaluateAnswerInterceptors(emptyList(), caseId, question, "yes", actor) shouldBe
            AnswerInterceptResult.Accept
    }

    "all interceptors accepting accepts the answer" {
        val interceptors = listOf(interceptor { AnswerInterceptResult.Accept }, interceptor { AnswerInterceptResult.Accept })

        service.evaluateAnswerInterceptors(interceptors, caseId, question, "yes", actor) shouldBe
            AnswerInterceptResult.Accept
    }

    "an interceptor that throws rejects the answer (fail-closed)" {
        val faulty =
            object : AnswerInterceptor {
                override suspend fun interceptAnswer(
                    caseId: UUID,
                    questionEvent: QuestionEvent,
                    answerText: String,
                    actor: Actor,
                ): AnswerInterceptResult = throw IllegalStateException("validator unreachable")
            }
        var captured: Throwable? = null

        val result =
            service.evaluateAnswerInterceptors(listOf(faulty), caseId, question, "yes", actor) { _, cause -> captured = cause }

        result.shouldBeInstanceOf<AnswerInterceptResult.Reject>()
        captured!!.message shouldBe "validator unreachable"
    }

    "a cancelled interceptor propagates the cancellation instead of rejecting" {
        val cancelled =
            object : AnswerInterceptor {
                override suspend fun interceptAnswer(
                    caseId: UUID,
                    questionEvent: QuestionEvent,
                    answerText: String,
                    actor: Actor,
                ): AnswerInterceptResult = throw CancellationException("case torn down")
            }
        var notified = false

        shouldThrow<CancellationException> {
            service.evaluateAnswerInterceptors(listOf(cancelled), caseId, question, "yes", actor) { _, _ -> notified = true }
        }
        notified shouldBe false
    }

    "the rejection reason distinguishes a failed check from an invalid answer" {
        val faulty = interceptor { throw RuntimeException("boom") }

        val result = service.evaluateAnswerInterceptors(listOf(faulty), caseId, question, "yes", actor)

        val reason = result.shouldBeInstanceOf<AnswerInterceptResult.Reject>().reason
        reason shouldContain "could not be validated"
        reason shouldContain "try again"
    }

    "the internal cause is never surfaced to the user" {
        val faulty = interceptor { throw IllegalStateException("jdbc://user:password@internal-host") }

        val result = service.evaluateAnswerInterceptors(listOf(faulty), caseId, question, "yes", actor)

        result.shouldBeInstanceOf<AnswerInterceptResult.Reject>().reason.contains("jdbc") shouldBe false
    }

    "an explicit rejection is returned as-is" {
        val result =
            service.evaluateAnswerInterceptors(
                listOf(interceptor { AnswerInterceptResult.Reject("not your question") }),
                caseId, question, "yes", actor,
            )

        result.shouldBeInstanceOf<AnswerInterceptResult.Reject>().reason shouldBe "not your question"
    }

    "ExternallyHandled stops processing" {
        val result =
            service.evaluateAnswerInterceptors(
                listOf(interceptor { AnswerInterceptResult.ExternallyHandled }),
                caseId, question, "yes", actor,
            )

        result shouldBe AnswerInterceptResult.ExternallyHandled
    }

    "evaluation short-circuits once an interceptor claims ownership" {
        var consulted = 0
        val owning = interceptor { AnswerInterceptResult.ExternallyHandled }
        val counting = interceptor { consulted++; AnswerInterceptResult.Accept }

        service.evaluateAnswerInterceptors(listOf(owning, counting), caseId, question, "yes", actor) shouldBe
            AnswerInterceptResult.ExternallyHandled
        consulted shouldBe 0
    }

    "a rejection outweighs a preceding acceptance" {
        val result =
            service.evaluateAnswerInterceptors(
                listOf(
                    interceptor { AnswerInterceptResult.Accept },
                    interceptor { AnswerInterceptResult.Reject("denied") },
                ),
                caseId, question, "yes", actor,
            )

        result.shouldBeInstanceOf<AnswerInterceptResult.Reject>()
    }

    // -------------------------------------------------------------------------
    // ToolGrantPolicy evaluation
    // -------------------------------------------------------------------------

    val toolContext =
        ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            agentName = "agent",
        )

    fun policy(decide: (String) -> ToolGrantDecision) =
        object : ToolGrantPolicy {
            override suspend fun evaluateToolGrant(toolName: String, context: ToolContext) = decide(toolName)
        }

    "no policy registered grants everything" {
        service.isToolGranted(emptyList(), "anyTool", toolContext) shouldBe true
    }

    "a neutral policy leaves the tool granted" {
        service.isToolGranted(listOf(policy { ToolGrantDecision.Neutral }), "readFile", toolContext) shouldBe true
    }

    "a policy that throws denies the tool (fail-closed)" {
        val faulty =
            object : ToolGrantPolicy {
                override suspend fun evaluateToolGrant(toolName: String, context: ToolContext): ToolGrantDecision =
                    throw IllegalStateException("policy backend unreachable")
            }
        var reason: String? = null

        val granted = service.isToolGranted(listOf(faulty), "editFiles", toolContext) { r, _, _ -> reason = r }

        granted shouldBe false
        reason!! shouldContain "fail-closed"
    }

    "a cancelled policy propagates the cancellation instead of denying" {
        val cancelled =
            object : ToolGrantPolicy {
                override suspend fun evaluateToolGrant(toolName: String, context: ToolContext): ToolGrantDecision =
                    throw CancellationException("run torn down")
            }
        var notified = false

        shouldThrow<CancellationException> {
            service.isToolGranted(listOf(cancelled), "editFiles", toolContext) { _, _, _ -> notified = true }
        }
        notified shouldBe false
    }

    "AllowOnly denies a tool outside the allow-list and grants one inside it" {
        val policies = listOf(policy { ToolGrantDecision.AllowOnly(setOf("readFile", "ls")) })

        service.isToolGranted(policies, "readFile", toolContext) shouldBe true
        service.isToolGranted(policies, "editFiles", toolContext) shouldBe false
    }

    "Deny denies a listed tool and leaves others untouched" {
        val policies = listOf(policy { ToolGrantDecision.Deny(setOf("editFiles"), "read-only run") })

        service.isToolGranted(policies, "editFiles", toolContext) shouldBe false
        service.isToolGranted(policies, "readFile", toolContext) shouldBe true
    }

    "a faulty policy denies even when surrounded by permissive ones" {
        val faulty =
            object : ToolGrantPolicy {
                override suspend fun evaluateToolGrant(toolName: String, context: ToolContext): ToolGrantDecision =
                    throw RuntimeException("boom")
            }
        val permissive = policy { ToolGrantDecision.Neutral }

        service.isToolGranted(listOf(faulty, permissive), "editFiles", toolContext) shouldBe false
        service.isToolGranted(listOf(permissive, faulty), "editFiles", toolContext) shouldBe false
    }

    "the denial reason is surfaced when a policy provides one" {
        val policies = listOf(policy { ToolGrantDecision.Deny(setOf("editFiles"), "read-only run") })
        var reason: String? = null

        service.isToolGranted(policies, "editFiles", toolContext) { r, _, _ -> reason = r }

        reason shouldBe "read-only run"
    }

    "one denial outweighs any number of permissive policies" {
        val policies =
            listOf(
                policy { ToolGrantDecision.AllowOnly(setOf("editFiles")) },
                policy { ToolGrantDecision.Neutral },
                policy { ToolGrantDecision.Deny(setOf("editFiles")) },
            )

        service.isToolGranted(policies, "editFiles", toolContext) shouldBe false
    }

    "evaluation short-circuits on the first denial" {
        var consulted = 0
        val denying = policy { ToolGrantDecision.Deny(setOf("editFiles")) }
        val counting =
            object : ToolGrantPolicy {
                override suspend fun evaluateToolGrant(toolName: String, context: ToolContext): ToolGrantDecision {
                    consulted++
                    return ToolGrantDecision.Neutral
                }
            }

        service.isToolGranted(listOf(denying, counting), "editFiles", toolContext) shouldBe false
        consulted shouldBe 0
    }

    // -------------------------------------------------------------------------
    // ExternalContextBindingRegistrar evaluation
    // -------------------------------------------------------------------------

    val bindingCaseId = UUID.randomUUID()
    val bindingNamespaceId = UUID.randomUUID()
    val attrs = mapOf("attemptId" to "abc")

    fun registrar(decide: () -> Boolean) =
        object : ExternalContextBindingRegistrar {
            override fun register(
                caseId: UUID,
                namespaceId: UUID,
                credential: String?,
                attributes: Map<String, String>,
                expiresAt: Instant?,
            ) = decide()
        }

    "no registrar returns false" {
        service.registerFirstBinding(emptyList(), bindingCaseId, bindingNamespaceId, null, attrs, null) shouldBe false
    }

    "first accepting registrar wins" {
        var secondCalled = false
        val accepting = registrar { true }
        val counting = registrar { secondCalled = true; false }

        service.registerFirstBinding(listOf(accepting, counting), bindingCaseId, bindingNamespaceId, null, attrs, null) shouldBe true
        secondCalled shouldBe false
    }

    "a throwing registrar is treated as rejection and next is tried" {
        val faulty = object : ExternalContextBindingRegistrar {
            override fun register(
                caseId: UUID,
                namespaceId: UUID,
                credential: String?,
                attributes: Map<String, String>,
                expiresAt: Instant?,
            ): Boolean = throw RuntimeException("backend down")
        }
        val accepting = registrar { true }
        var errorCaptured = false

        val result =
            service.registerFirstBinding(
                listOf(faulty, accepting),
                bindingCaseId, bindingNamespaceId, "secret", attrs, null,
            ) { _, _ -> errorCaptured = true }

        result shouldBe true
        errorCaptured shouldBe true
    }

    "CancellationException from a registrar propagates" {
        val cancelled = object : ExternalContextBindingRegistrar {
            override fun register(
                caseId: UUID,
                namespaceId: UUID,
                credential: String?,
                attributes: Map<String, String>,
                expiresAt: Instant?,
            ): Boolean = throw CancellationException("scope torn down")
        }

        shouldThrow<CancellationException> {
            service.registerFirstBinding(listOf(cancelled), bindingCaseId, bindingNamespaceId, null, attrs, null)
        }
    }
})
