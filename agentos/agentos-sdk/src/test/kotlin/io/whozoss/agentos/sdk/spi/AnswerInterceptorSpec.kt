package io.whozoss.agentos.sdk.spi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import java.util.UUID

/**
 * Guards the fail-closed contract of [AnswerInterceptor.evaluate].
 *
 * An interceptor is a gate. One that cannot answer has not granted passage: accepting
 * on failure would let an answer through precisely when the check meant to validate it
 * is broken.
 */
class AnswerInterceptorSpec : StringSpec({

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
            override fun interceptAnswer(
                caseId: UUID,
                questionEvent: QuestionEvent,
                answerText: String,
                actor: Actor,
            ) = decide()
        }

    "no interceptor registered accepts the answer" {
        AnswerInterceptor.evaluate(emptyList(), caseId, question, "yes", actor) shouldBe
            AnswerInterceptResult.Accept
    }

    "all interceptors accepting accepts the answer" {
        val interceptors = listOf(interceptor { AnswerInterceptResult.Accept }, interceptor { AnswerInterceptResult.Accept })

        AnswerInterceptor.evaluate(interceptors, caseId, question, "yes", actor) shouldBe
            AnswerInterceptResult.Accept
    }

    // The invariant this whole helper exists for.
    "an interceptor that throws rejects the answer" {
        val faulty =
            object : AnswerInterceptor {
                override fun interceptAnswer(
                    caseId: UUID,
                    questionEvent: QuestionEvent,
                    answerText: String,
                    actor: Actor,
                ): AnswerInterceptResult = throw IllegalStateException("validator unreachable")
            }
        var captured: Throwable? = null

        val result =
            AnswerInterceptor.evaluate(listOf(faulty), caseId, question, "yes", actor) { _, cause -> captured = cause }

        result.shouldBeInstanceOf<AnswerInterceptResult.Reject>()
        captured!!.message shouldBe "validator unreachable"
    }

    // The user must learn that validation failed, not that their answer was wrong.
    "the rejection reason distinguishes a failed check from an invalid answer" {
        val faulty =
            object : AnswerInterceptor {
                override fun interceptAnswer(
                    caseId: UUID,
                    questionEvent: QuestionEvent,
                    answerText: String,
                    actor: Actor,
                ): AnswerInterceptResult = throw RuntimeException("boom")
            }

        val result = AnswerInterceptor.evaluate(listOf(faulty), caseId, question, "yes", actor)

        val reason = result.shouldBeInstanceOf<AnswerInterceptResult.Reject>().reason
        reason shouldContain "could not be validated"
        reason shouldContain "try again"
    }

    "the internal cause is never surfaced to the user" {
        val faulty =
            object : AnswerInterceptor {
                override fun interceptAnswer(
                    caseId: UUID,
                    questionEvent: QuestionEvent,
                    answerText: String,
                    actor: Actor,
                ): AnswerInterceptResult = throw IllegalStateException("jdbc://user:password@internal-host")
            }

        val result = AnswerInterceptor.evaluate(listOf(faulty), caseId, question, "yes", actor)

        result.shouldBeInstanceOf<AnswerInterceptResult.Reject>().reason.contains("jdbc") shouldBe false
    }

    "an explicit rejection is returned as-is" {
        val interceptors = listOf(interceptor { AnswerInterceptResult.Reject("not your question") })

        val result = AnswerInterceptor.evaluate(interceptors, caseId, question, "yes", actor)

        result.shouldBeInstanceOf<AnswerInterceptResult.Reject>().reason shouldBe "not your question"
    }

    "ExternallyHandled stops processing and is reported" {
        val interceptors = listOf(interceptor { AnswerInterceptResult.ExternallyHandled })

        AnswerInterceptor.evaluate(interceptors, caseId, question, "yes", actor) shouldBe
            AnswerInterceptResult.ExternallyHandled
    }

    // Once one interceptor owns the continuation, the others must not also act.
    "evaluation short-circuits once an interceptor claims ownership" {
        var consulted = 0
        val owning = interceptor { AnswerInterceptResult.ExternallyHandled }
        val counting = interceptor { consulted++; AnswerInterceptResult.Accept }

        AnswerInterceptor.evaluate(listOf(owning, counting), caseId, question, "yes", actor) shouldBe
            AnswerInterceptResult.ExternallyHandled
        consulted shouldBe 0
    }

    "a rejection outweighs a preceding acceptance" {
        val interceptors =
            listOf(
                interceptor { AnswerInterceptResult.Accept },
                interceptor { AnswerInterceptResult.Reject("denied") },
            )

        AnswerInterceptor.evaluate(interceptors, caseId, question, "yes", actor)
            .shouldBeInstanceOf<AnswerInterceptResult.Reject>()
    }

    "the default implementation accepts" {
        val bare = object : AnswerInterceptor {}

        bare.interceptAnswer(caseId, question, "yes", actor) shouldBe AnswerInterceptResult.Accept
    }
})
