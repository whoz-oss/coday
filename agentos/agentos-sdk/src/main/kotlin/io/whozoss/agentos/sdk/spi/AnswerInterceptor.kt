package io.whozoss.agentos.sdk.spi

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import org.pf4j.ExtensionPoint
import java.util.UUID

/**
 * Outcome of an [AnswerInterceptor.interceptAnswer] call.
 */
sealed interface AnswerInterceptResult {
    /** The answer is accepted and processed as usual. */
    data object Accept : AnswerInterceptResult

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
 * only when every interceptor returns [AnswerInterceptResult.Accept].
 *
 * ### Safe default
 *
 * [interceptAnswer] defaults to [AnswerInterceptResult.Accept], so the contract is a
 * pure no-op for implementations that only need to observe, and existing behavior is
 * preserved when no interceptor is registered.
 *
 * Implementations that do not need to run any logic should simply not be registered.
 * An interceptor must never throw for an expected rejection — it must return
 * [AnswerInterceptResult.Reject] instead. Unexpected exceptions are caught by the
 * caller and treated as [AnswerInterceptResult.Accept] (fail-open) to avoid blocking
 * the case on a faulty hook.
 */
interface AnswerInterceptor : ExtensionPoint {
    /**
     * Validate an answer before it is turned into an [io.whozoss.agentos.sdk.caseEvent.AnswerEvent].
     *
     * @param caseId the case the answer belongs to.
     * @param questionEvent the question being answered.
     * @param answerText the textual answer supplied by [actor].
     * @param actor the user (or actor) providing the answer.
     * @return [AnswerInterceptResult.Accept] to continue, or
     *   [AnswerInterceptResult.Reject] with a human-readable reason to stop.
     */
    fun interceptAnswer(
        caseId: UUID,
        questionEvent: QuestionEvent,
        answerText: String,
        actor: Actor,
    ): AnswerInterceptResult = AnswerInterceptResult.Accept
}
