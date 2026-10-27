package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Reads a frozen plan back from the case history and tells where it stands.
 *
 * Only the `ToolResponseEvent` of an `AGENTOS_CONFIG__propose` call is trusted, followed by the
 * `QuestionEvent` it announced, from the same agent, for the same user, with an intact hash.
 * Anything else answers as if there were no plan.
 *
 * Trust assumption: only the service writes these two events. No API lets a client create case
 * events (the event endpoints only read, the message endpoints only add messages and answers), and
 * the question's id is the plan's `questionId` only when it comes from `AgentInterrupt.AwaitAnswer`,
 * which in-service tools alone can raise (plugins and MCP servers only reach the SDK). The hash
 * therefore guards integrity, not authenticity: if an API ever lets clients write events, the plan
 * must be signed with a server-held key.
 */
@Component
class ConfigPlanReader(
    private val caseEventService: CaseEventService,
    private val configPlanner: ConfigPlanner,
    private val objectMapper: ObjectMapper,
) {
    /** Reads the whole case history; reading only the events a plan needs is #1518. */
    fun load(
        caseId: UUID,
        questionId: UUID,
    ): LoadedPlan {
        val events = caseEventService.findByParent(caseId)
        val planIndex = events.indexOfFirst { isPlanFor(it, questionId) }
        return trustedPlan(events, planIndex, caseId, questionId)
            ?: throw ResourceNotFoundException("No configuration plan for question $questionId in this case")
    }

    /** The plan at [planIndex] when the question [questionId] that follows it announces it, else null. */
    private fun trustedPlan(
        events: List<CaseEvent>,
        planIndex: Int,
        caseId: UUID,
        questionId: UUID,
    ): LoadedPlan? {
        val questionIndex = events.indexOfFirst { it is QuestionEvent && it.id == questionId }
        val question = events.getOrNull(questionIndex) as? QuestionEvent
        val planJson = (events.getOrNull(planIndex) as? ToolResponseEvent)?.structuredOutput
        val plan = planJson?.let { runCatching { objectMapper.treeToValue(it, ConfigChangePlan::class.java) }.getOrNull() }
        return if (
            plan != null &&
            question != null &&
            planIndex < questionIndex &&
            plan.schema == ConfigAgentIntegration.PLAN_SCHEMA &&
            plan.caseId == caseId &&
            plan.questionId == questionId &&
            plan.agentName == question.agentName &&
            plan.recipientUserId == question.userId &&
            configPlanner.planHash(planJson) == plan.planHash
        ) {
            LoadedPlan(plan, question, planIndex, events)
        } else {
            null
        }
    }

    /**
     * The state of [loaded] at [now]. [claimed] is whether an apply or reject currently holds the
     * plan; the caller holding it passes false so its own claim does not block it.
     */
    fun state(
        loaded: LoadedPlan,
        now: Instant,
        claimed: Boolean,
    ): ConfigChangeState =
        when {
            isAnswered(loaded.question, loaded.events) -> ConfigChangeState.ANSWERED
            claimed -> ConfigChangeState.IN_PROGRESS
            now.isAfter(loaded.plan.expiresAt) -> ConfigChangeState.EXPIRED
            hasNewerPlan(loaded) -> ConfigChangeState.SUPERSEDED
            else -> ConfigChangeState.OPEN
        }

    private fun isPlanFor(
        event: CaseEvent,
        questionId: UUID,
    ): Boolean = proposedQuestionId(event) == questionId

    /** Same rule as the case runtime: only the addressee's answer closes a question. */
    private fun isAnswered(
        question: QuestionEvent,
        events: List<CaseEvent>,
    ): Boolean =
        events.any {
            it is AnswerEvent && it.questionId == question.id && (question.userId == null || it.actor.id == question.userId.toString())
        }

    /**
     * A newer trusted plan in the same case replaces this one for good, whether it has been answered or
     * not. Only a plan its own question announces counts, so any other tool response carrying the propose
     * tool's name cannot close the plans of a case.
     */
    private fun hasNewerPlan(
        loaded: LoadedPlan,
    ): Boolean =
        loaded.events.withIndex().drop(loaded.planIndex + 1).any { (index, event) ->
            proposedQuestionId(event)?.let { trustedPlan(loaded.events, index, loaded.plan.caseId, it) } != null
        }

    /** The question a propose tool response announces, or null for any other event. */
    private fun proposedQuestionId(event: CaseEvent): UUID? =
        (event as? ToolResponseEvent)
            ?.takeIf { it.toolName == ConfigAgentIntegration.PROPOSE_TOOL_NAME }
            ?.structuredOutput
            ?.path("questionId")
            ?.asText()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
