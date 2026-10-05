package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.spi.AnswerInterceptResult
import io.whozoss.agentos.sdk.spi.AnswerInterceptor
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.pf4j.Extension
import java.util.UUID

/**
 * Validates a user's answer against the Factory before the runtime persists the
 * [io.whozoss.agentos.sdk.caseEvent.AnswerEvent].
 *
 * The Factory checkpoint is held entirely inside the plugin ([FactoryBridgeServices.pendingCheckpoints]):
 * [io.whozoss.agentos.plugins.factorybridge.tools.FactoryRequestHumanDecisionTool] registers it when it
 * opens an interaction, and this interceptor consumes it when the user answers. Cases without an open
 * checkpoint are passed through untouched (the safe default), so ordinary questions are unaffected.
 * When a checkpoint is present, the decision is submitted to the Factory interaction-reply endpoint; a
 * Factory rejection surfaces as [AnswerInterceptResult.Reject] so the runtime keeps the agent suspended
 * and the user can retry.
 *
 * The interface is synchronous, mirroring
 * `io.whozoss.agentos.caseFlow.CaseRuntime.addUserMessage`, so the suspend client call is
 * bridged with [runBlocking].
 */
@Extension
class FactoryAnswerInterceptor
    @JvmOverloads
    constructor(
        private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
    ) : AnswerInterceptor {
        private val checkpointClient: FactoryCheckpointClient by lazy {
            val resolved = services()
            FactoryCheckpointClient(resolved.config.baseUrl, resolved.httpClient, resolved.objectMapper)
        }

        override fun interceptAnswer(
            caseId: UUID,
            questionEvent: QuestionEvent,
            answerText: String,
            actor: Actor,
        ): AnswerInterceptResult {
            val resolved = services()
            // Standard AgentOS queryUser questions always stay under AgentOS
            // authority: persist AnswerEvent and resume the same run. The
            // legacy step-question map is intentionally not consumed here;
            // old Factory questions remain explicit legacy state and are not
            // silently converted to the standard channel.
            val checkpoint = resolved.pendingCheckpoints[caseId]
                ?: return AnswerInterceptResult.Accept
            val result =
                runBlocking {
                    checkpointClient.submitDecision(
                        ref = checkpoint,
                        decision = answerText,
                        caseId = caseId.toString(),
                        actorId = actor.id,
                        namespaceId = questionEvent.namespaceId.toString(),
                        stepQuestion = false,
                    )
                }
            return result.fold(
                onSuccess = {
                    resolved.pendingCheckpoints.remove(caseId, checkpoint)
                    AnswerInterceptResult.Accept
                },
                onFailure = { error ->
                    val reason = error.message ?: "Factory rejected the decision"
                    logger.warn { "Factory rejected answer for case=$caseId workflow=${checkpoint.workflowId}: $reason" }
                    AnswerInterceptResult.Reject(reason)
                },
            )
        }

        companion object : KLogging()
    }
