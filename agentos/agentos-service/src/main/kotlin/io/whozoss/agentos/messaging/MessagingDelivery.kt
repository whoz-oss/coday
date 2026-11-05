package io.whozoss.agentos.messaging

import io.whozoss.agentos.caseEvent.CaseEventCreatedEvent
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.messagingConversationId
import io.whozoss.agentos.configAgent.ConfigAgentIntegration
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeApi
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.user.UserService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends what a case says back to the chat its current turn came from.
 *
 * The rule is the one of a conversation: the answer goes where the question came from. The last user message
 * of a case says where it came from ([io.whozoss.agentos.caseFlow.SessionContextKeys.MESSAGING]); a message
 * typed in the UI says nothing, and its answer stays in the UI. "Continue on Telegram", a `/cases` choice or a
 * button answer point the case at their chat with [redirect]. A message can only point its case at a chat of
 * its own author: the session context of a message comes from its client, which could name any conversation,
 * so a chat that belongs to someone else receives nothing.
 *
 * Only what a person reads is sent: the agent's messages and questions, warnings and errors (no default agent,
 * an unknown `@name`, a run stopped at its iteration limit...), and a typing indicator while the case runs. A
 * configuration plan of the config agent is sent as its card ([MessagingPlanCards]), read through the card's
 * endpoint as the chat's user, with Apply and Reject. Tool calls, thinking and sub-cases stay in AgentOS, one
 * click away.
 *
 * Events arrive from [CaseEventCreatedEvent], on the thread of the case: the listener only records the origin
 * and queues, the sending happens on [SerialLanes] per conversation, so that the replies of one chat keep their
 * order and a slow chat holds no other back.
 *
 * Origins are kept in memory. After a restart a case is delivered again from its next user message; a run in
 * progress at the restart is lost anyway, its runtime being in memory too.
 */
@Component
class MessagingDelivery(
    private val registry: MessagingBotRegistry,
    private val conversations: MessagingConversationService,
    private val caseService: CaseService,
    private val userService: UserService,
    private val configChanges: ConfigChangeApi,
    private val links: MessagingLinks,
    private val clock: Clock,
) {
    /** For each case, the conversation its current turn came from. */
    private val turnOrigins = ConcurrentHashMap<UUID, TurnOrigin>()

    /** For each case a chat follows, the question of the configuration plan its agent just proposed. */
    private val proposedPlans = ConcurrentHashMap<UUID, UUID>()
    private val typing = ConcurrentHashMap<UUID, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lanes = SerialLanes<UUID>(scope, name = "MessagingDelivery")

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    /** Cheap for a case no chat follows, the common case: one map lookup. */
    @EventListener
    fun onCaseEventCreated(created: CaseEventCreatedEvent) {
        val event = created.event
        when {
            event is MessageEvent && event.actor.role == ActorRole.USER -> trackOrigin(event)
            event is ToolResponseEvent -> trackPlan(event)
            else ->
                turnOrigins[event.caseId]
                    ?.takeIf { isDelivered(event) }
                    ?.let { origin -> lanes.submit(origin.conversationId) { deliver(event, origin) } }
        }
        if (event is CaseStatusEvent && event.status.isTerminal()) {
            turnOrigins.remove(event.caseId)
            proposedPlans.remove(event.caseId)
        }
    }

    /** Sends what [caseId] says next to [conversationId], until the next user message says otherwise. */
    fun redirect(
        caseId: UUID,
        conversationId: UUID,
    ) {
        turnOrigins[caseId] = TurnOrigin(conversationId, authorId = null)
    }

    private fun trackOrigin(message: MessageEvent) {
        when (val origin = message.sessionContext?.messagingConversationId()) {
            null -> turnOrigins.remove(message.caseId)
            else -> turnOrigins[message.caseId] = TurnOrigin(origin, authorId = message.actor.id)
        }
    }

    /** Remembers the question a plan announces, so that the question goes out as the plan's card. */
    private fun trackPlan(response: ToolResponseEvent) {
        if (response.toolName == ConfigAgentIntegration.PROPOSE_TOOL_NAME && turnOrigins.containsKey(response.caseId)) {
            response.structuredOutput
                ?.path("questionId")
                ?.asText()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.let { questionId -> proposedPlans[response.caseId] = questionId }
        }
    }

    private fun isDelivered(event: CaseEvent): Boolean =
        when (event) {
            is MessageEvent -> event.actor.role == ActorRole.AGENT
            is QuestionEvent, is CaseStatusEvent, is WarnEvent, is ErrorEvent -> true
            else -> false
        }

    private fun deliver(
        event: CaseEvent,
        origin: TurnOrigin,
    ) {
        val conversation = conversations.findById(origin.conversationId)
        val adapter = conversation?.let { registry.adapter(it.platform, it.accountId) }
        when {
            conversation == null || adapter == null ->
                logger.debug { "[MessagingDelivery] conversation ${origin.conversationId} or its bot is gone, ${event.caseId} not delivered" }
            origin.authorId != null && origin.authorId != conversation.userId.toString() ->
                logger.warn {
                    "[MessagingDelivery] a message of ${origin.authorId} named the conversation ${conversation.id} of another user, " +
                        "${event.caseId} not delivered there"
                }
            event is CaseStatusEvent -> deliverStatus(event, conversation, adapter)
            else ->
                render(event, conversation)?.let { message -> adapter.send(conversation.chatId, inCaseOf(conversation, event.caseId, message)) }
        }
    }

    private fun deliverStatus(
        event: CaseStatusEvent,
        conversation: MessagingConversation,
        adapter: MessagingAdapter,
    ) {
        when (event.status) {
            CaseStatus.RUNNING -> startTyping(conversation, adapter)
            else -> typing.remove(conversation.id)?.cancel()
        }
        if (event.status == CaseStatus.ERROR) {
            adapter.send(
                conversation.chatId,
                OutboundMessage("The case stopped on an error. Send ${adapter.commandPrefix}new to start a new one."),
            )
        }
    }

    /** What a person reads of [event], or null when there is nothing to read. */
    private fun render(
        event: CaseEvent,
        conversation: MessagingConversation,
    ): OutboundMessage? =
        when (event) {
            is MessageEvent ->
                event.content
                    .filterIsInstance<MessageContent.Text>()
                    .joinToString("\n\n") { it.content }
                    .takeIf { it.isNotBlank() }
                    ?.let { OutboundMessage(it) }
            is QuestionEvent ->
                if (proposedPlans.remove(event.caseId, event.id)) renderPlan(event, conversation) else renderQuestion(event)
            is WarnEvent -> OutboundMessage("⚠️ ${event.message.take(MAX_NOTICE_LENGTH)}")
            is ErrorEvent -> OutboundMessage("⚠️ ${event.message.take(MAX_NOTICE_LENGTH)}")
            else -> null
        }

    private fun renderQuestion(question: QuestionEvent): OutboundMessage =
        when {
            question.questionType == QuestionType.OAUTH_AUTHORIZE ->
                OutboundMessage(
                    text = "The agent needs your authorization to go on.",
                    buttons = listOf(MessagingButton("Authorize", MessagingAction.OpenUrl(question.question))),
                )
            question.options.isNullOrEmpty() -> OutboundMessage(question.question)
            else ->
                OutboundMessage(
                    text = question.question + if (question.questionType == QuestionType.OPEN_CHOICE) OPEN_CHOICE_HINT else "",
                    buttons =
                        question.options.orEmpty().mapIndexed { index, option ->
                            MessagingButton(
                                label = option.take(MAX_OPTION_LABEL_LENGTH),
                                action = MessagingAction.AnswerQuestion(questionId = question.id, optionIndex = index),
                            )
                        },
                )
        }

    /**
     * The card of the plan [question] asks about, read through the card's endpoint as the chat's user, who may
     * apply it only if it was proposed to them. The plain question when the card cannot be read.
     */
    private fun renderPlan(
        question: QuestionEvent,
        conversation: MessagingConversation,
    ): OutboundMessage {
        val viewer = userService.findById(conversation.userId)
        val view =
            viewer?.let { user ->
                runCatching { asUser(user) { configChanges.viewConfigChange(question.caseId, question.id) } }
                    .onFailure { logger.warn(it) { "[MessagingDelivery] the plan of question ${question.id} cannot be read, sent as a question" } }
                    .getOrNull()
            }
        return when {
            viewer == null || view == null -> renderQuestion(question)
            else -> MessagingPlanCards.card(view, viewer.id, clock.instant(), links.case(question.namespaceId, question.caseId))
        }
    }

    /** Names the case when the chat has moved on to another one since. */
    private fun inCaseOf(
        conversation: MessagingConversation,
        caseId: UUID,
        message: OutboundMessage,
    ): OutboundMessage =
        when (conversation.caseId) {
            caseId -> message
            else -> {
                val title = caseService.findById(caseId)?.title ?: "another case"
                message.copy(text = "📁 **$title**\n\n${message.text}")
            }
        }

    /** Keeps the indicator on while the case runs: platforms clear it after a few seconds. */
    private fun startTyping(
        conversation: MessagingConversation,
        adapter: MessagingAdapter,
    ) {
        val until = Instant.now().plus(MAX_TYPING)
        typing.put(
            conversation.id,
            scope.launch {
                while (isActive && Instant.now().isBefore(until)) {
                    adapter.showTyping(conversation.chatId)
                    delay(TYPING_REFRESH.toMillis())
                }
            },
        )?.cancel()
    }

    /**
     * Where the current turn of a case came from: [conversationId], named by the message of [authorId], or by
     * AgentOS itself when null ([redirect]).
     */
    private data class TurnOrigin(
        val conversationId: UUID,
        val authorId: String?,
    )

    companion object : KLogging() {
        private const val MAX_NOTICE_LENGTH = 500
        private const val MAX_OPTION_LABEL_LENGTH = 60
        private const val OPEN_CHOICE_HINT = "\n\n_Choose, or reply with your own answer._"
        private val TYPING_REFRESH: Duration = Duration.ofSeconds(4)

        /** A run that keeps going longer still runs, the indicator just stops. */
        private val MAX_TYPING: Duration = Duration.ofMinutes(15)
    }
}
