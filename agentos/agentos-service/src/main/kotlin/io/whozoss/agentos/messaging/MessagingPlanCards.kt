package io.whozoss.agentos.messaging

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.api.configChange.ConfigAction
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeDecision
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeReportDto
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeState
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeViewDto
import io.whozoss.agentos.sdk.api.configChange.ConfigDeployTarget
import io.whozoss.agentos.sdk.api.configChange.ConfigOperationResultDto
import io.whozoss.agentos.sdk.api.configChange.ConfigOperationStatus
import io.whozoss.agentos.sdk.api.configChange.ConfigOperationViewDto
import io.whozoss.agentos.sdk.api.configChange.ConfigResultStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A configuration plan of the config agent as a chat shows it, with the options of its card in the AgentOS UI:
 * Apply and Reject, which [MessagingGateway] carries out at the click through the card's endpoints.
 *
 * The message is built from the plan the server froze, as the card reads it ([ConfigChangeViewDto]), never from
 * what the agent wrote: each operation with its fields before and after (current values masked as the card
 * masks them), the warnings, the expiry. A plan that needs a secret is not applied from a chat: a secret typed
 * there would stay in the chat's history, on the platform's servers. The message sends the user to the card
 * for it; Reject stays.
 */
object MessagingPlanCards {
    private const val MAX_VALUE_LENGTH = 120
    private const val MAX_CREATED_FIELDS = 6
    private const val MAX_RESULT_MESSAGE_LENGTH = 200
    private const val MINUTES_PER_HOUR = 60L

    const val NOT_RECIPIENT_TEXT = "Only the person this plan was proposed to can apply or reject it."
    const val SECRET_TEXT = "This plan needs a secret: enter it in the AgentOS card to apply it. You can reject it here."

    /** The plan of [view] as [viewerId] reads it at [now], with the buttons [viewerId] may use. */
    fun card(
        view: ConfigChangeViewDto,
        viewerId: UUID,
        now: Instant,
        caseLink: String?,
    ): OutboundMessage {
        val text =
            buildString {
                append("🛠 **").append(view.agentName).append(" proposes a configuration change**\n\n")
                append(view.summary.trim())
                view.operations.sortedBy { it.order }.forEach { op ->
                    append("\n\n").append(op.order).append(". **").append(title(op)).append("**")
                    statusOf(op.status)?.let { append(" · ").append(it) }
                    details(op).forEach { append("\n• ").append(it) }
                }
                view.warnings.forEach { append("\n\n⚠️ ").append(it) }
                append("\n\n").append(footer(view, viewerId, now))
            }
        val buttons =
            buildList {
                if (view.canApply && !needsSecret(view)) {
                    add(
                        MessagingButton(
                            label = "Apply",
                            action = MessagingAction.ApplyPlan(view.questionId),
                            confirm = "Apply this plan to the AgentOS configuration, with your rights?",
                        ),
                    )
                }
                if (view.canReject) add(MessagingButton("Reject", MessagingAction.RejectPlan(view.questionId)))
                caseLink?.let { add(MessagingButton("Open in AgentOS", MessagingAction.OpenUrl(it))) }
            }
        return OutboundMessage(text, buttons)
    }

    /** What an apply did, operation by operation, for the chat of the user who clicked, [by]. */
    fun applied(
        view: ConfigChangeViewDto,
        report: ConfigChangeReportDto,
        by: String,
    ): String =
        buildString {
            append(
                when (report.decision) {
                    ConfigChangeDecision.APPLIED -> "✅ **Plan applied** by $by"
                    ConfigChangeDecision.PARTIAL -> "⚠️ **Plan partly applied** by $by"
                    ConfigChangeDecision.FAILED, ConfigChangeDecision.REJECTED -> "❌ **Plan not applied**: nothing was written"
                },
            )
            report.results.forEach { result ->
                val op = view.operations.firstOrNull { it.ref == result.ref }
                append("\n").append(op?.let { "${it.order}. ${title(it)}" } ?: result.ref).append(": ").append(outcomeOf(result))
            }
            if (!report.answerPosted) append("\n\nThe agent could not be told: apply again to tell it.")
        }

    fun rejected(by: String): String = "✖️ **Plan rejected** by $by. The agent is told."

    /** Whether an operation still to write needs a secret the user must type. */
    fun needsSecret(view: ConfigChangeViewDto): Boolean =
        view.operations.any { it.secretInputs.isNotEmpty() && it.status == ConfigOperationStatus.PENDING }

    /** Why a plan in [state] can no longer be applied. */
    fun closedText(state: ConfigChangeState): String =
        when (state) {
            ConfigChangeState.OPEN -> "This plan is open."
            ConfigChangeState.IN_PROGRESS -> "This plan is being applied or rejected right now."
            ConfigChangeState.ANSWERED -> "This plan was already applied or rejected."
            ConfigChangeState.EXPIRED -> "This plan has expired: ask the agent for a new one."
            ConfigChangeState.SUPERSEDED -> "A newer plan replaces this one."
        }

    private fun footer(
        view: ConfigChangeViewDto,
        viewerId: UUID,
        now: Instant,
    ): String =
        when {
            view.state != ConfigChangeState.OPEN -> closedText(view.state)
            viewerId != view.recipientUserId -> NOT_RECIPIENT_TEXT
            needsSecret(view) -> SECRET_TEXT
            else -> "_Expires in ${remaining(Duration.between(now, view.expiresAt))}._"
        }

    private fun remaining(left: Duration): String {
        val minutes = left.toMinutes().coerceAtLeast(1)
        return if (minutes < MINUTES_PER_HOUR * 2) "$minutes min" else "${minutes / MINUTES_PER_HOUR} h"
    }

    /** `Update agent Assistant`, `Create integration GitHub in Sales, copy of GitHub prod`... */
    private fun title(op: ConfigOperationViewDto): String =
        buildString {
            append(verbOf(op.action)).append(' ').append(op.kind.name.lowercase().replace('_', ' '))
            if (op.name.isNotBlank()) append(' ').append(op.name)
            op.namespaceName?.let { append(" in ").append(it) }
            op.copyFrom?.let { append(", copy of ").append(it) }
            if (op.action == ConfigAction.CREATE && op.deployTo == ConfigDeployTarget.NAMESPACE) append(", deployed to the namespace")
        }

    private fun verbOf(action: ConfigAction): String = action.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun statusOf(status: ConfigOperationStatus): String? =
        when (status) {
            ConfigOperationStatus.PENDING -> null
            ConfigOperationStatus.APPLIED, ConfigOperationStatus.ALREADY_APPLIED -> "already done"
            ConfigOperationStatus.CONFLICT -> "changed since the proposal"
            ConfigOperationStatus.NOT_VISIBLE -> "not visible to you"
        }

    private fun details(op: ConfigOperationViewDto): List<String> {
        val changes =
            op.changes.map { change ->
                "`${change.path}`: ${valueOf(change.before)} → ${if (change.remove) "removed" else valueOf(change.after)}"
            }
        val created =
            op.value
                ?.takeIf { op.action == ConfigAction.CREATE && it.isObject }
                ?.properties()
                ?.filter { (field, _) -> field != "name" }
                .orEmpty()
        val fields = created.take(MAX_CREATED_FIELDS).map { (field, value) -> "`$field`: ${valueOf(value)}" }
        val more = (created.size - MAX_CREATED_FIELDS).takeIf { it > 0 }?.let { listOf("and $it more fields, in AgentOS") }.orEmpty()
        val secrets = op.secretInputs.map { "🔑 ${it.label}: to enter in AgentOS" }
        return changes + fields + more + secrets
    }

    /** A value on one line, as code so that the chat renders it as is. */
    private fun valueOf(node: JsonNode?): String {
        val raw =
            when {
                node == null || node.isNull || node.isMissingNode -> return "none"
                node.isTextual -> node.asText()
                else -> node.toString()
            }
        val line = raw.replace(Regex("\\s+"), " ").replace('`', '\'').trim()
        return "`" + (if (line.length > MAX_VALUE_LENGTH) line.take(MAX_VALUE_LENGTH) + "…" else line).ifEmpty { " " } + "`"
    }

    private fun outcomeOf(result: ConfigOperationResultDto): String {
        val outcome =
            when (result.status) {
                ConfigResultStatus.APPLIED -> "done"
                ConfigResultStatus.ALREADY_APPLIED -> "already done"
                ConfigResultStatus.DENIED -> "refused"
                ConfigResultStatus.NOT_FOUND -> "not found"
                ConfigResultStatus.CONFLICT -> "conflict"
                ConfigResultStatus.INVALID -> "invalid"
                ConfigResultStatus.FAILED -> "failed"
                ConfigResultStatus.NOT_RUN -> "not run"
            }
        val message =
            result.message
                ?.takeUnless { result.status.isSuccess || result.status == ConfigResultStatus.NOT_RUN }
                ?.take(MAX_RESULT_MESSAGE_LENGTH)
        return message?.let { "$outcome ($it)" } ?: outcome
    }
}
