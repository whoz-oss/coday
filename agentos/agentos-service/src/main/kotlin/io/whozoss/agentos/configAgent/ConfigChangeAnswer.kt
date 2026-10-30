package io.whozoss.agentos.configAgent

import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.user.User
import mu.KLogging
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Writes and posts the answer that closes a plan's question and resumes the agent.
 *
 * The text is replayed to the LLM as the user's message. It is built from fixed templates, the
 * operation refs and kinds, the entity names the plan itself proposed, the result codes and, for a
 * failed operation, the REST layer's own error message truncated to [MAX_MESSAGE_LENGTH]. It never
 * carries a secret: secrets only travel in the apply request, and no error message repeats them.
 *
 * [caseService] is `@Lazy`: `CaseService` depends on `AgentService`, which builds the config tools.
 */
@Component
class ConfigChangeAnswer(
    @Lazy private val caseService: CaseService,
) {
    fun applied(
        plan: ConfigChangePlan,
        report: ConfigChangeReportDto,
        caller: User,
    ): String =
        buildString {
            append(header(plan.questionId, report.decision, caller))
            report.results.forEach { result ->
                plan.operation(result.ref)?.let { op -> append('\n').append(line(op, result)) }
            }
        }

    fun rejected(
        questionId: UUID,
        caller: User,
        comment: String?,
    ): String =
        buildString {
            append(header(questionId, ConfigChangeDecision.REJECTED, caller))
            comment?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\nComment: ").append(it) }
        }

    /**
     * Answers the question. Returns false instead of throwing when the case refuses the message (e.g.
     * it was closed meanwhile): the configuration may already be written, so the caller must still get
     * its report, and the plan stays open so that applying it again re-sends the answer.
     */
    fun post(
        caseId: UUID,
        questionId: UUID,
        caller: User,
        text: String,
    ): Boolean {
        val actor = Actor(id = caller.id.toString(), displayName = displayName(caller), role = ActorRole.USER)
        return runCatching {
            caseService.addMessage(caseId = caseId, actor = actor, content = listOf(MessageContent.Text(text)), answerToEventId = questionId)
        }.onFailure { logger.warn(it) { "[ConfigChange] could not answer question $questionId in case $caseId" } }
            .isSuccess
    }

    private fun header(
        questionId: UUID,
        decision: ConfigChangeDecision,
        caller: User,
    ) = "[${ConfigAgentIntegration.TYPE} change ${questionId.toString().take(SHORT_ID_LENGTH)} $decision by ${displayName(caller)}]"

    private fun line(
        op: PlannedOperation,
        result: ConfigOperationResultDto,
    ): String =
        buildString {
            append(ConfigProposeTool.describe(op)).append(": ").append(result.status)
            result.entityId?.takeIf { op.action == ConfigAction.CREATE }?.let { append(" (id ").append(it).append(')') }
            result.message?.takeUnless { result.status.isSuccess || result.status == ConfigResultStatus.NOT_RUN }?.let {
                append(" - ").append(it.take(MAX_MESSAGE_LENGTH))
            }
        }

    private fun displayName(user: User): String = listOfNotNull(user.firstname, user.lastname).joinToString(" ").ifBlank { user.metadata.id.toString() }

    companion object : KLogging() {
        const val MAX_MESSAGE_LENGTH = 200
        private const val SHORT_ID_LENGTH = 8
    }
}
