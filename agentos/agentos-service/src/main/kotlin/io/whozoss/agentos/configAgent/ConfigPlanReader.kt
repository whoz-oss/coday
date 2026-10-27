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
 */
@Component
class ConfigPlanReader(
    private val caseEventService: CaseEventService,
    private val configPlanner: ConfigPlanner,
    private val objectMapper: ObjectMapper,
) {
    fun load(
        caseId: UUID,
        questionId: UUID,
    ): LoadedPlan {
        val events = caseEventService.findByParent(caseId)
        val questionIndex = events.indexOfFirst { it is QuestionEvent && it.id == questionId }
        val planIndex = events.indexOfFirst { isPlanFor(it, questionId) }
        val question = events.getOrNull(questionIndex) as? QuestionEvent
        val planJson = (events.getOrNull(planIndex) as? ToolResponseEvent)?.structuredOutput
        val plan = planJson?.let { runCatching { objectMapper.treeToValue(it, ConfigChangePlan::class.java) }.getOrNull() }
        val trusted =
            question != null &&
                plan != null &&
                planIndex < questionIndex &&
                plan.schema == ConfigAgentIntegration.PLAN_SCHEMA &&
                plan.caseId == caseId &&
                plan.questionId == questionId &&
                plan.agentName == question.agentName &&
                plan.recipientUserId == question.userId &&
                configPlanner.planHash(planJson) == plan.planHash
        if (!trusted) throw ResourceNotFoundException("No configuration plan for question $questionId in this case")
        return LoadedPlan(plan!!, question!!, planIndex, events)
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
            hasNewerPlan(loaded.planIndex, loaded.events) -> ConfigChangeState.SUPERSEDED
            else -> ConfigChangeState.OPEN
        }

    private fun isPlanFor(
        event: CaseEvent,
        questionId: UUID,
    ): Boolean =
        event is ToolResponseEvent &&
            event.toolName == ConfigAgentIntegration.PROPOSE_TOOL_NAME &&
            event.structuredOutput?.path("questionId")?.asText() == questionId.toString()

    /** Same rule as the case runtime: only the addressee's answer closes a question. */
    private fun isAnswered(
        question: QuestionEvent,
        events: List<CaseEvent>,
    ): Boolean =
        events.any {
            it is AnswerEvent && it.questionId == question.id && (question.userId == null || it.actor.id == question.userId.toString())
        }

    /** A newer plan in the same case replaces this one for good, whether it has been answered or not. */
    private fun hasNewerPlan(
        planIndex: Int,
        events: List<CaseEvent>,
    ): Boolean =
        events
            .drop(planIndex + 1)
            .any { it is ToolResponseEvent && it.toolName == ConfigAgentIntegration.PROPOSE_TOOL_NAME && it.structuredOutput != null }
}
