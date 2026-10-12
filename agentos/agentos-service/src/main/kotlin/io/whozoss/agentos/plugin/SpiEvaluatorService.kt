package io.whozoss.agentos.plugin

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.spi.AnswerInterceptResult
import io.whozoss.agentos.sdk.spi.AnswerInterceptor
import io.whozoss.agentos.sdk.spi.ExternalContextBindingRegistrar
import io.whozoss.agentos.sdk.spi.ToolGrantDecision
import io.whozoss.agentos.sdk.spi.ToolGrantPolicy
import io.whozoss.agentos.sdk.tool.ToolContext
import kotlinx.coroutines.CancellationException
import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Fail-closed evaluation of the three SPI extension points that gate runtime decisions:
 * [AnswerInterceptor], [ToolGrantPolicy] and [ExternalContextBindingRegistrar].
 *
 * A hook that throws is never treated as consent.
 */
@Service
class SpiEvaluatorService {

    /**
     * Consult every [AnswerInterceptor] and return the resulting verdict.
     *
     * A throwing interceptor rejects the answer (fail-closed); [CancellationException]
     * is rethrown as-is. Evaluation short-circuits on the first non-Accept outcome.
     *
     * @param onError notified when an interceptor throws; must never throw itself.
     */
    suspend fun evaluateAnswerInterceptors(
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(interceptor, e)
                    return AnswerInterceptResult.Reject(
                        "Your answer could not be validated because a validation step failed. " +
                            "The question is still open \u2014 please try again.",
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

    /**
     * Evaluate every [ToolGrantPolicy] for one tool and return whether it may be granted.
     *
     * A tool is granted only when no policy denies it. A throwing policy denies the tool
     * (fail-closed); [CancellationException] is rethrown as-is. Evaluation
     * short-circuits on the first denial.
     *
     * @param onDenied notified with a human-readable reason on every denial; must never throw.
     */
    suspend fun isToolGranted(
        policies: Iterable<ToolGrantPolicy>,
        toolName: String,
        context: ToolContext,
        onDenied: (reason: String, policy: ToolGrantPolicy, cause: Throwable?) -> Unit = { _, _, _ -> },
    ): Boolean {
        policies.forEach { policy ->
            val decision =
                try {
                    policy.evaluateToolGrant(toolName, context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onDenied("policy threw while evaluating '$toolName'; denying (fail-closed)", policy, e)
                    return false
                }
            when (decision) {
                is ToolGrantDecision.Neutral -> Unit

                is ToolGrantDecision.AllowOnly ->
                    if (toolName !in decision.toolNames) {
                        onDenied("'$toolName' is not in the policy's allow-list", policy, null)
                        return false
                    }

                is ToolGrantDecision.Deny ->
                    if (toolName in decision.toolNames) {
                        onDenied(decision.reason ?: "'$toolName' is denied by policy", policy, null)
                        return false
                    }
            }
        }
        return true
    }

    /**
     * Offer a binding to each [ExternalContextBindingRegistrar] in turn and return
     * whether one accepted it.
     *
     * First registrar to return `true` wins. A throwing registrar is treated as a
     * rejection and the next one is tried.
     *
     * @param onError notified when a registrar throws; must never throw.
     *   Must not log [credential] or attribute values.
     */
    fun registerFirstBinding(
        registrars: Iterable<ExternalContextBindingRegistrar>,
        caseId: UUID,
        namespaceId: UUID,
        credential: String?,
        attributes: Map<String, String>,
        expiresAt: Instant?,
        onError: (registrar: ExternalContextBindingRegistrar, cause: Throwable) -> Unit = { _, _ -> },
    ): Boolean =
        registrars.any { registrar ->
            try {
                registrar.register(caseId, namespaceId, credential, attributes, expiresAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(registrar, e)
                false
            }
        }

    companion object : KLogging()
}
