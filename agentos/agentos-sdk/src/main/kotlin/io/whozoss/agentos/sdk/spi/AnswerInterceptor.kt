package io.whozoss.agentos.sdk.spi

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import org.pf4j.ExtensionPoint
import java.util.UUID

/**
 * Outcome of an [AnswerInterceptor.interceptAnswer] call.
 */
sealed interface AnswerInterceptResult {
    /** The answer is accepted and processed as usual by AgentOS. */
    data object Accept : AnswerInterceptResult

    /**
     * The answer was accepted and fully handled by the interceptor. AgentOS acknowledges
     * the user message without persisting an AnswerEvent or resuming the existing case.
     */
    data object ExternallyHandled : AnswerInterceptResult

    /**
     * The answer is rejected. No [io.whozoss.agentos.sdk.caseEvent.AnswerEvent] is persisted
     * and the agent is not resumed; the reason is surfaced to the user.
     */
    data class Reject(val reason: String) : AnswerInterceptResult
}

/**
 * Generic SPI extension point that intercepts and validates a user's answer to a
 * [QuestionEvent] before the answer is persisted and the agent turn is resumed.
 *
 * Implementations are discovered as extensions (e.g. via PF4J) and/or registered as
 * ordinary beans. Several interceptors may be active at once; the answer is accepted
 * only when every interceptor returns [AnswerInterceptResult.Accept]. An
 * [AnswerInterceptResult.ExternallyHandled] result ends processing successfully because
 * the interceptor has transferred ownership of the continuation to an external system.
 *
 * ### Safe default
 *
 * [interceptAnswer] defaults to [AnswerInterceptResult.Accept], so the contract is a
 * pure no-op for implementations that only need to observe, and existing behavior is
 * preserved when no interceptor is registered.
 *
 * Implementations that do not need to run any logic should simply not be registered.
 *
 * ### Exception handling — fail-closed
 *
 * Use [evaluate] to consult interceptors: one that throws rejects the answer.
 *
 * An interceptor must never throw for an expected rejection — it must return
 * [AnswerInterceptResult.Reject] instead. An interceptor is a gate: one that cannot
 * answer has not granted passage. Accepting on failure would let an answer through
 * precisely when the check meant to validate it is broken.
 *
 * The reason surfaced on a faulty interceptor says that validation could not be
 * completed, not that the answer was invalid — the two are different facts and the
 * user is owed the right one. A rejection is recoverable: the question stands and the
 * user may retry.
 */
interface AnswerInterceptor : ExtensionPoint {
    /**
     * Validate an answer before it is turned into an [io.whozoss.agentos.sdk.caseEvent.AnswerEvent].
     *
     * @param caseId the case the answer belongs to.
     * @param questionEvent the question being answered.
     * @param answerText the textual answer supplied by [actor].
     * @param actor the user (or actor) providing the answer.
     * @return [AnswerInterceptResult.Accept] to continue normal processing,
     *   [AnswerInterceptResult.ExternallyHandled] when no local answer or resumption must occur,
     *   or [AnswerInterceptResult.Reject] with a human-readable reason to stop.
     */
    fun interceptAnswer(
        caseId: UUID,
        questionEvent: QuestionEvent,
        answerText: String,
        actor: Actor,
    ): AnswerInterceptResult = AnswerInterceptResult.Accept

    companion object {
        /**
         * Consult every interceptor and return the resulting verdict.
         *
         * Lives in the SDK so the fail-closed rule is applied once, here, rather than
         * re-implemented at each call site — where a `runCatching { … }.getOrElse { … }`
         * could quietly pick the permissive branch.
         *
         * Resolution, in order of precedence:
         *
         * - an interceptor that **throws** → [AnswerInterceptResult.Reject];
         * - any [AnswerInterceptResult.Reject] → that rejection, immediately;
         * - any [AnswerInterceptResult.ExternallyHandled] → ownership of the
         *   continuation has been transferred, stop and report it;
         * - otherwise → [AnswerInterceptResult.Accept].
         *
         * Evaluation short-circuits on the first non-[AnswerInterceptResult.Accept]
         * outcome: the verdict can no longer change, and once an interceptor claims
         * ownership the remaining ones must not also act on the same answer.
         *
         * @param onError notified when an interceptor throws, so the host can log the
         *   cause that the user-facing reason deliberately omits; must never throw.
         */
        fun evaluate(
            interceptors: Iterable<AnswerInterceptor>,
            caseId: UUID,
            questionEvent: QuestionEvent,
            answerText: String,
            actor: Actor,
            onError: (interceptor: AnswerInterceptor, cause: Throwable) -> Unit = { _, _ -> },
        ): AnswerInterceptResult {
            interceptors.forEach { interceptor ->
                val result =
                    try {
                        interceptor.interceptAnswer(caseId, questionEvent, answerText, actor)
                    } catch (e: Exception) {
                        onError(interceptor, e)
                        return AnswerInterceptResult.Reject(
                            "Your answer could not be validated because a validation step failed. " +
                                "The question is still open — please try again.",
                        )
                    }
                when (result) {
                    is AnswerInterceptResult.Accept -> Unit
                    is AnswerInterceptResult.Reject -> return result
                    is AnswerInterceptResult.ExternallyHandled -> return result
                }
            }
            return AnswerInterceptResult.Accept
        }
    }
}
