package io.whozoss.agentos.messaging

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.AgentMention
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.SessionContextKeys
import io.whozoss.agentos.context.UserSessionContextResolver
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.case.AddMessageRequest
import io.whozoss.agentos.sdk.api.case.CaseApi
import io.whozoss.agentos.sdk.api.case.CaseDto
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeApi
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeApplyRequest
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeDecision
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeState
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeViewDto
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Takes in what the bot of an agent receives and turns it into case messages to that agent, as the user the
 * account is linked to.
 *
 * A bot is one agent's ([MessagingBot.agentId]), in one namespace: talking to the bot is talking to the agent,
 * and the cases it starts belong to that namespace. A direct chat holds one conversation ([MessagingConversation])
 * whose messages go to one case at a time: the first message starts a case, the next ones follow it, and `/new`,
 * `/cases` or a "Continue on Telegram" request from the UI move the chat to another case. A message goes to the
 * bot's agent, named at the start of the message (`@agent`) when the case would otherwise go to another one: the
 * agent runs as the linked user, with that user's rights and credentials. A configuration plan the agent proposes
 * is applied or rejected from its buttons, as from its card in the UI.
 *
 * What the gateway does for a chat runs with the linked user authenticated ([asUser]) and goes through the
 * endpoints the UI calls, by their `*Api` beans: [CaseApi] to read, start and feed the cases, [ConfigChangeApi]
 * to apply or reject a plan. Every action of a chat is thus authorized by the same `@PreAuthorize` and inline
 * guards as the same action in the UI, and an action refused there is refused here. The gateway itself only
 * checks what belongs to the bot: a linked account, an agent the user may use, a case of the bot's namespace.
 *
 * Every message carries its origin in its session context ([SessionContextKeys.MESSAGING]), which is how
 * [MessagingDelivery] knows to send the reply back to the chat.
 *
 * A voice message is transcribed on the server ([VoiceTranscriber]) and taken in as the text it says, marked 🎤;
 * the chat is shown that text first, so that the user sees what the agent will read. A message the bot cannot
 * read (a photo, a file...) is answered with what it reads, rather than met with silence.
 *
 * Group chats are not handled yet: their messages are ignored.
 */
@Service
class MessagingGateway(
    private val identities: MessagingIdentityService,
    private val conversations: MessagingConversationService,
    private val cases: CaseApi,
    private val configChanges: ConfigChangeApi,
    private val caseService: CaseService,
    private val caseEventService: CaseEventService,
    private val userService: UserService,
    private val agentConfigService: AgentConfigService,
    private val bots: MessagingBotService,
    private val permissionService: PermissionService,
    private val sessionContextResolver: UserSessionContextResolver,
    private val delivery: MessagingDelivery,
    private val registry: MessagingBotRegistry,
    private val links: MessagingLinks,
    private val transcriber: VoiceTranscriber,
) {
    /** Handles what [adapter] received. Never throws: a failure is logged and, when possible, told to the sender. */
    fun handle(
        adapter: MessagingAdapter,
        inbound: MessagingInbound,
    ) {
        try {
            when {
                inbound.sender.bot -> logger.debug { "[Messaging] ignoring the bot ${inbound.sender.externalUserId}" }
                inbound is MessagingInbound.Text -> handleText(adapter, inbound)
                inbound is MessagingInbound.Voice -> handleVoice(adapter, inbound)
                inbound is MessagingInbound.Unsupported -> handleUnsupported(adapter, inbound)
                inbound is MessagingInbound.Click -> handleClick(adapter, inbound)
                inbound is MessagingInbound.BotManaged -> logger.debug { "[Messaging] @${adapter.bot.username} manages no bot" }
            }
        } catch (e: AccessDeniedException) {
            logger.info { "[Messaging] AgentOS refused a ${adapter.platform} update from chat ${inbound.chatId}: ${e.message}" }
            runCatching { adapter.send(inbound.chatId, OutboundMessage(DENIED_TEXT)) }
        } catch (e: Exception) {
            logger.warn(e) { "[Messaging] failed to handle a ${adapter.platform} update from chat ${inbound.chatId}" }
            runCatching { adapter.send(inbound.chatId, OutboundMessage(FAILURE_TEXT)) }
        }
    }

    /**
     * Moves [user]'s direct chat with the bot [botId] to [caseId] and posts the case's last answer there, so that
     * the user continues the case from the chat, with the bot's agent.
     *
     * Runs as [user], whom the calling endpoint authorized on the case.
     *
     * @throws ConflictException when the bot does not run, works in another namespace than the case, or the user
     *   has linked no account through it
     * @throws ResourceNotFoundException when the case does not exist
     * @throws MessagingDeliveryException when the platform refuses the message
     */
    fun continueCaseIn(
        botId: UUID,
        caseId: UUID,
        user: User,
    ): MessagingConversation {
        val adapter = registry.adapter(botId) ?: throw ConflictException("This bot is not running")
        val bot = adapter.bot
        val case = asUser(user) { readCase(caseId) } ?: throw ResourceNotFoundException("Case not found: $caseId")
        if (case.namespaceId != bot.namespaceId) throw ConflictException("@${bot.username} answers in another namespace than this case")
        val identity =
            identities.findByUser(user.id, bot.platform, bot.accountId)
                ?: throw ConflictException("Link your ${bot.platform} account to @${bot.username} first")
        val conversation =
            conversations
                .open(bot.platform, bot.accountId, identity.directChatId, user.id)
                .let { opened -> conversations.update(opened) { it.copy(caseId = caseId) } }
        delivery.redirect(caseId, conversation.id)
        adapter.send(identity.directChatId, OutboundMessage(caseSummary(case, CONTINUE_HINT)))
        logger.info { "[Messaging] case $caseId continued with @${bot.username} by user ${user.id}" }
        return conversation
    }

    // ======================================================
    // Text messages
    // ======================================================

    private fun handleText(
        adapter: MessagingAdapter,
        inbound: MessagingInbound.Text,
    ) {
        val command = MessagingCommand.parse(inbound.text)
        when {
            !inbound.direct -> logger.debug { "[Messaging] ignoring a group message of chat ${inbound.chatId}" }
            command?.linksAccount == true -> link(adapter, inbound, requireNotNull(command.argument))
            else ->
                withChat(adapter, inbound.chatId, inbound.sender) { chat ->
                    takeIn(chat, inbound.messageId) { current ->
                        when (command?.name) {
                            MessagingCommand.START, MessagingCommand.HELP -> reply(current, helpText(current))
                            MessagingCommand.NEW -> startNewCase(current)
                            MessagingCommand.CASE -> showCurrentCase(current)
                            MessagingCommand.CASES -> listCases(current)
                            else -> post(current, inbound.text)
                        }
                    }
                }
        }
    }

    /**
     * Runs [block] for the message [messageId] of [chat] the first time it arrives, with the message recorded as
     * taken in: a platform may deliver a message again, after a restart.
     */
    private fun takeIn(
        chat: Chat,
        messageId: Long,
        block: (Chat) -> Unit,
    ) {
        if (chat.conversation.lastInboundMessageId?.let { messageId <= it } == true) {
            logger.debug { "[Messaging] message $messageId of ${chat.chatId} already taken in" }
            return
        }
        val recorded = conversations.update(chat.conversation) { it.copy(lastInboundMessageId = messageId) }
        block(chat.copy(conversation = recorded))
    }

    // ======================================================
    // Voice messages, and what the bot cannot read
    // ======================================================

    /** Transcribes a voice message, shows the chat what it says, and posts that as a text message. */
    private fun handleVoice(
        adapter: MessagingAdapter,
        inbound: MessagingInbound.Voice,
    ) {
        if (!inbound.direct) {
            logger.debug { "[Messaging] ignoring a group voice message of chat ${inbound.chatId}" }
            return
        }
        withChat(adapter, inbound.chatId, inbound.sender) { chat ->
            takeIn(chat, inbound.messageId) { current ->
                transcribe(current, inbound.audio)?.let { words ->
                    reply(current, "$VOICE_MARK $words")
                    post(current, "$VOICE_MARK $words")
                }
            }
        }
    }

    /** The words of [audio]; null, and the chat told why, when there are none to post. */
    private fun transcribe(
        chat: Chat,
        audio: MessagingAudio,
    ): String? {
        val tooLong = (audio.durationSeconds ?: 0) > transcriber.maxDurationSeconds || (audio.sizeBytes ?: 0) > transcriber.maxBytes
        val refusal =
            when {
                !transcriber.available -> VOICE_OFF_TEXT
                tooLong -> "This voice message is too long: I transcribe up to ${durationText(transcriber.maxDurationSeconds)}. Split it, or write it."
                else -> null
            }
        if (refusal != null) {
            reply(chat, refusal)
            return null
        }
        chat.adapter.showTyping(chat.chatId)
        val words =
            try {
                transcriber.transcribe(chat.adapter.downloadAudio(audio, transcriber.maxBytes))
            } catch (e: MessagingDeliveryException) {
                logger.warn(e) { "[Messaging] could not download a voice message of chat ${chat.chatId}" }
                null
            } catch (e: TranscriptionException) {
                logger.warn { "[Messaging] could not transcribe a voice message of chat ${chat.chatId}: ${e.message}" }
                null
            }
        return when {
            words == null -> null.also { reply(chat, VOICE_FAILED_TEXT) }
            words.isBlank() -> null.also { reply(chat, NO_WORDS_TEXT) }
            else -> words
        }
    }

    private fun handleUnsupported(
        adapter: MessagingAdapter,
        inbound: MessagingInbound.Unsupported,
    ) {
        if (!inbound.direct) return
        withChat(adapter, inbound.chatId, inbound.sender) { chat ->
            takeIn(chat, inbound.messageId) { current ->
                reply(current, if (transcriber.available) UNSUPPORTED_TEXT else UNSUPPORTED_NO_VOICE_TEXT)
            }
        }
    }

    private fun durationText(seconds: Int): String =
        if (seconds % SECONDS_PER_MINUTE == 0) "${seconds / SECONDS_PER_MINUTE} min" else "$seconds s"

    private fun link(
        adapter: MessagingAdapter,
        inbound: MessagingInbound.Text,
        token: String,
    ) {
        val account =
            LinkedAccount(
                platform = adapter.platform,
                accountId = adapter.accountId,
                externalUserId = inbound.sender.externalUserId,
                directChatId = inbound.chatId,
                displayName = inbound.sender.displayName,
            )
        val text =
            identities.link(token, adapter.bot, account)?.let { identity ->
                val name = userService.findById(identity.userId)?.displayName() ?: identity.userId.toString()
                val agent = agentOf(adapter)?.name?.let { "**$it**" } ?: "your agent"
                "Your account is now linked to AgentOS as **$name**. Write to talk to $agent. " +
                    "${adapter.commandPrefix}help lists the commands."
            } ?: "This link has expired or was already used. Ask for a new one in your AgentOS profile${profileSuffix()}."
        adapter.send(inbound.chatId, OutboundMessage(text))
    }

    /** Posts [text] to the chat's case, starting one when the chat has none it can still write to. */
    private fun post(
        chat: Chat,
        text: String,
    ) {
        val current = currentCase(chat)
        when {
            current != null && !current.status.isTerminal() -> {
                addMessage(chat, current, addressed(chat, current.caseId, text))
                chat.adapter.showTyping(chat.chatId)
            }
            else -> {
                if (current != null) reply(chat, "Your previous case is closed: this message starts a new one.")
                startCaseWith(chat, text)
            }
        }
    }

    /** Starts a case as the UI does (the endpoint makes its creator its admin), then posts [text] to it. */
    private fun startCaseWith(
        chat: Chat,
        text: String,
    ) {
        val case = cases.create(CaseDto(namespaceId = chat.namespaceId))
        try {
            conversations.update(chat.conversation) { it.copy(caseId = case.caseId) }
            addMessage(chat, case, addressed(chat, null, text))
        } catch (e: Exception) {
            // The failure itself is logged by handle(); this only records the compensation.
            logger.warn { "[Messaging] starting case ${case.caseId} from chat ${chat.chatId} failed, deleting it" }
            runCatching { caseService.delete(case.caseId) }
                .onFailure { logger.warn(it) { "[Messaging] could not delete the half-started case ${case.caseId}" } }
            throw e
        }
        chat.adapter.showTyping(chat.chatId)
        logger.info { "[Messaging] case ${case.caseId} started with @${chat.adapter.bot.username} by user ${chat.user.id}" }
    }

    /**
     * [text], addressed to the bot's agent when the case would otherwise go to another one: a message naming an
     * agent goes to that one, and a case whose last agent is the bot's needs no name (the case keeps its agent).
     */
    private fun addressed(
        chat: Chat,
        caseId: UUID?,
        text: String,
    ): String {
        // A selection names its agent: its id is derived from that name, not the agent's own.
        val lastAgentName =
            caseId?.let { caseEventService.findByParent(it).filterIsInstance<AgentSelectedEvent>().lastOrNull()?.agentName }
        return when {
            AgentMention.extractName(text) != null || lastAgentName.equals(chat.agent.name, ignoreCase = true) -> text
            else -> "@${chat.agent.name} $text"
        }
    }

    /** Posts [text] to [case] as its user, through the endpoint the UI posts to. */
    private fun addMessage(
        chat: Chat,
        case: CaseDto,
        text: String,
        answerToEventId: UUID? = null,
    ) {
        cases.addMessage(
            case.caseId,
            AddMessageRequest(content = text, answerToEventId = answerToEventId, sessionContext = sessionContext(chat, case.namespaceId)),
        )
    }

    /**
     * The context of a message from the chat: the user's context, as a scheduled prompt or a started case gets
     * it, and where the message comes from. A context provider that fails does not hold the message back: it
     * goes without that part.
     */
    private fun sessionContext(
        chat: Chat,
        namespaceId: UUID,
    ): Map<String, Any?> {
        val provided =
            when (val result = sessionContextResolver.resolve(chat.user.externalId, namespaceId)) {
                is UserContextResult.Success -> result.sessionContext
                is UserContextResult.PermanentFailure, is UserContextResult.TransientFailure -> {
                    logger.warn { "[Messaging] no user context for user ${chat.user.id}, the message goes without it" }
                    null
                }
            }
        val withLanguage =
            sessionContextResolver.mergePreferredLanguage(
                providerContext = provided,
                preferredLanguage = chat.user.preferredLanguage?.takeIf { it.isNotBlank() },
            )
        val origin =
            mapOf(
                SessionContextKeys.PLATFORM to chat.adapter.platform.name,
                SessionContextKeys.CONVERSATION_ID to chat.conversation.id.toString(),
            )
        return (withLanguage ?: emptyMap()) + (SessionContextKeys.MESSAGING to origin)
    }

    // ======================================================
    // Commands
    // ======================================================

    private fun startNewCase(chat: Chat) {
        conversations.update(chat.conversation) { it.copy(caseId = null) }
        reply(chat, "Your next message starts a new case with **${chat.agent.name}**.")
    }

    private fun showCurrentCase(chat: Chat) {
        val case = currentCase(chat)
        val text =
            when (case) {
                null -> "No case yet: your next message starts one with **${chat.agent.name}**."
                else -> caseSummary(case, "Status: ${case.status.name.lowercase()}.")
            }
        reply(chat, text)
    }

    /** The user's recent cases of the bot's namespace, as the case list of the UI has them: the agent answers there only. */
    private fun listCases(chat: Chat) {
        val recent =
            cases
                .listMineByParent(chat.namespaceId)
                .filter { it.parentCaseId == null }
                .sortedByDescending { it.lastMessageAt ?: it.modified }
                .take(MAX_LISTED_CASES)
        val message =
            when {
                recent.isEmpty() -> OutboundMessage("You have no case here yet: send a message to start one.")
                else ->
                    OutboundMessage(
                        text = "Your recent cases. Choose the one to continue here:",
                        buttons =
                            recent.map { case ->
                                val marker = if (case.caseId == chat.conversation.caseId) "• " else ""
                                MessagingButton(
                                    label = marker + case.name.take(MAX_LABEL_LENGTH),
                                    action = MessagingAction.OpenCase(case.caseId),
                                )
                            },
                    )
            }
        chat.adapter.send(chat.chatId, message)
    }

    // ======================================================
    // Button clicks
    // ======================================================

    private fun handleClick(
        adapter: MessagingAdapter,
        inbound: MessagingInbound.Click,
    ) {
        val notice =
            try {
                when (val access = findChat(adapter, inbound.chatId, inbound.sender)) {
                    is ChatAccess.Granted ->
                        asUser(access.chat.user) {
                            when (val action = inbound.action) {
                                is MessagingAction.AnswerQuestion -> answer(access.chat, action, inbound.messageId)
                                is MessagingAction.OpenCase -> openCase(access.chat, action.caseId)
                                is MessagingAction.ApplyPlan -> applyPlan(access.chat, action.questionId, inbound.messageId)
                                is MessagingAction.RejectPlan -> rejectPlan(access.chat, action.questionId, inbound.messageId)
                                is MessagingAction.OpenUrl, null -> EXPIRED_BUTTON_NOTICE
                            }
                        }
                    is ChatAccess.Refused -> access.notice
                }
            } catch (e: MessagingRefusal) {
                e.message
            } catch (e: AccessDeniedException) {
                logger.info { "[Messaging] AgentOS refused a click in chat ${inbound.chatId}: ${e.message}" }
                DENIED_NOTICE
            }
        adapter.acknowledgeClick(inbound.clickId, notice.take(MAX_NOTICE_LENGTH))
    }

    /** Answers a question with the chosen option, as the question panel of the UI does. */
    private fun answer(
        chat: Chat,
        action: MessagingAction.AnswerQuestion,
        messageId: String,
    ): String {
        val question =
            caseEventService.findById(action.questionId) as? QuestionEvent
                ?: throw MessagingRefusal("This question no longer exists.")
        val case = writableCase(chat, question.caseId) ?: throw MessagingRefusal("You can no longer answer in this case.")
        val option =
            question.options?.getOrNull(action.optionIndex)
                ?: throw MessagingRefusal("This choice no longer exists.")
        when {
            question.userId != null && question.userId != chat.user.id ->
                throw MessagingRefusal("This question is for someone else.")
            caseEventService.findByParent(case.caseId).any { it is AnswerEvent && it.questionId == question.id } ->
                throw MessagingRefusal("This question was already answered.")
        }
        // An answer carries no session context: the reply follows the chat it was given in, from before the run.
        delivery.redirect(case.caseId, chat.conversation.id)
        addMessage(chat, case, option, answerToEventId = question.id)
        chat.adapter.removeButtons(chat.chatId, messageId)
        chat.adapter.showTyping(chat.chatId)
        return "✓ $option"
    }

    private fun openCase(
        chat: Chat,
        caseId: UUID,
    ): String {
        val case =
            writableCase(chat, caseId)?.takeIf { it.namespaceId == chat.namespaceId }
                ?: throw MessagingRefusal("You can no longer write in this case.")
        conversations.update(chat.conversation) { it.copy(caseId = caseId) }
        delivery.redirect(caseId, chat.conversation.id)
        reply(chat, caseSummary(case, CONTINUE_HINT))
        return "✓ ${case.name}"
    }

    /**
     * Applies the plan the question [questionId] asks about, through the card's endpoint: only the user it was
     * proposed to, who may write to the case, gets it applied, with their own rights for each write. The plan
     * applied is the one the endpoint reads back from the case, bound by its hash.
     */
    private fun applyPlan(
        chat: Chat,
        questionId: UUID,
        messageId: String,
    ): String {
        val view = recipientsPlan(chat, questionId, messageId)
        // The agent's next answer comes back here, whichever chat or page the plan was proposed from.
        delivery.redirect(view.caseId, chat.conversation.id)
        val report =
            planCall(chat, messageId) {
                configChanges.applyConfigChange(view.caseId, questionId, ConfigChangeApplyRequest(expectedPlanHash = view.planHash))
            }
        // A plan whose answer could not reach the agent stays open: applying it again sends the answer.
        if (report.answerPosted) chat.adapter.removeButtons(chat.chatId, messageId)
        reply(chat, MessagingPlanCards.applied(view, report, chat.user.displayName()))
        if (report.answerPosted) chat.adapter.showTyping(chat.chatId)
        return when (report.decision) {
            ConfigChangeDecision.APPLIED -> "✓ Applied"
            ConfigChangeDecision.PARTIAL -> "✓ Partly applied"
            ConfigChangeDecision.FAILED, ConfigChangeDecision.REJECTED -> "Not applied: nothing was written."
        }
    }

    /** Rejects the plan the question [questionId] asks about, through the card's endpoint. */
    private fun rejectPlan(
        chat: Chat,
        questionId: UUID,
        messageId: String,
    ): String {
        val view = recipientsPlan(chat, questionId, messageId)
        delivery.redirect(view.caseId, chat.conversation.id)
        planCall(chat, messageId) { configChanges.rejectConfigChange(view.caseId, questionId, null) }
        chat.adapter.removeButtons(chat.chatId, messageId)
        reply(chat, MessagingPlanCards.rejected(chat.user.displayName()))
        chat.adapter.showTyping(chat.chatId)
        return "✓ Rejected"
    }

    /**
     * The plan of [questionId] as the card shows it to the chat's user, who must be the one it was proposed to.
     * The endpoints check it again; checking it first keeps anyone else from pointing the case at their chat.
     */
    private fun recipientsPlan(
        chat: Chat,
        questionId: UUID,
        messageId: String,
    ): ConfigChangeViewDto {
        val question = caseEventService.findById(questionId) as? QuestionEvent ?: throw MessagingRefusal(PLAN_GONE_NOTICE)
        val view = planCall(chat, messageId) { configChanges.viewConfigChange(question.caseId, questionId) }
        if (view.recipientUserId != chat.user.id) throw MessagingRefusal(MessagingPlanCards.NOT_RECIPIENT_TEXT)
        return view
    }

    /**
     * [call] to the card's endpoint, its refusals told as the card tells them. The buttons of a plan that can no
     * longer change are removed.
     */
    private fun <T> planCall(
        chat: Chat,
        messageId: String,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (e: AccessDeniedException) {
            throw MessagingRefusal(
                if (e.message.orEmpty().startsWith(NOT_RECIPIENT)) MessagingPlanCards.NOT_RECIPIENT_TEXT else "You cannot change the plans of this case.",
            )
        } catch (e: ResourceNotFoundException) {
            throw MessagingRefusal(PLAN_GONE_NOTICE)
        } catch (e: UnprocessableEntityException) {
            throw MessagingRefusal(MessagingPlanCards.SECRET_TEXT)
        } catch (e: ConflictException) {
            val state = ConfigChangeState.entries.firstOrNull { e.message.orEmpty().startsWith("${it.name}:") }
            if (state == ConfigChangeState.ANSWERED || state == ConfigChangeState.SUPERSEDED) chat.adapter.removeButtons(chat.chatId, messageId)
            throw MessagingRefusal(state?.let(MessagingPlanCards::closedText) ?: "The plan changed since it was shown: open it in AgentOS.")
        }

    // ======================================================
    // Helpers
    // ======================================================

    /** The chat's case, when its user may still write to it. */
    private fun currentCase(chat: Chat): CaseDto? = chat.conversation.caseId?.let { writableCase(chat, it) }

    /**
     * [caseId] read through its endpoint, when the chat's user may also write to it: whether the chat can keep
     * using the case. The writes themselves are authorized by their own endpoints.
     */
    private fun writableCase(
        chat: Chat,
        caseId: UUID,
    ): CaseDto? = readCase(caseId)?.takeIf { canWriteCase(chat.user, caseId) }

    /** [caseId] read through its endpoint as the current user; null when it is gone or hidden from them. */
    private fun readCase(caseId: UUID): CaseDto? =
        try {
            cases.getById(caseId)
        } catch (e: ResourceNotFoundException) {
            null
        } catch (e: AccessDeniedException) {
            null
        }

    /** Runs [block] for a sender who may talk to the bot's agent, as that user; tells anyone else why not. */
    private fun withChat(
        adapter: MessagingAdapter,
        chatId: String,
        sender: MessagingSender,
        block: (Chat) -> Unit,
    ) {
        when (val access = findChat(adapter, chatId, sender)) {
            is ChatAccess.Granted -> asUser(access.chat.user) { block(access.chat) }
            is ChatAccess.Refused -> adapter.send(chatId, OutboundMessage(access.text))
        }
    }

    /** The chat of [sender] with the bot, when the account is linked and its user may use the bot's agent. */
    private fun findChat(
        adapter: MessagingAdapter,
        chatId: String,
        sender: MessagingSender,
    ): ChatAccess {
        val user =
            identities
                .findByAccount(adapter.platform, adapter.accountId, sender.externalUserId)
                ?.let { identity -> userService.findById(identity.userId) }
                ?: return ChatAccess.Refused(notLinkedText(adapter), NOT_LINKED_NOTICE)
        val agent = agentOf(adapter) ?: return ChatAccess.Refused(NO_AGENT_TEXT, NO_AGENT_TEXT)
        val namespaceId = requireNotNull(adapter.bot.namespaceId) { "The bot of an agent has a namespace" }
        if (bots.usableAgent(user.id, adapter.bot) == null) {
            val text = "You cannot talk to **${agent.name}**: ask an admin of its namespace in AgentOS for access."
            return ChatAccess.Refused(text, "You cannot talk to ${agent.name}.")
        }
        return ChatAccess.Granted(
            Chat(
                adapter = adapter,
                chatId = chatId,
                user = user,
                agent = agent,
                namespaceId = namespaceId,
                conversation = conversations.open(adapter.platform, adapter.accountId, chatId, user.id),
            ),
        )
    }

    private fun agentOf(adapter: MessagingAdapter): AgentConfig? = adapter.bot.agentId?.let { agentConfigService.findById(it) }

    private fun caseSummary(
        case: CaseDto,
        hint: String,
    ): String {
        val lastAnswer =
            caseEventService
                .findByParent(case.caseId)
                .lastOrNull { it is MessageEvent && it.actor.role == ActorRole.AGENT }
                ?.let { message ->
                    (message as MessageEvent).content.filterIsInstance<MessageContent.Text>().joinToString("\n") { it.content }
                }
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.length > MAX_EXCERPT_LENGTH) it.take(MAX_EXCERPT_LENGTH) + "…" else it }
        return listOfNotNull(
            "📁 **${case.name}**",
            lastAnswer,
            hint,
            links.case(case.namespaceId, case.caseId)?.let { "[Open in AgentOS]($it)" },
        ).joinToString("\n\n")
    }

    private fun reply(
        chat: Chat,
        text: String,
    ) = chat.adapter.send(chat.chatId, OutboundMessage(text))

    private fun notLinkedText(adapter: MessagingAdapter): String =
        "This account is not linked to AgentOS yet. Open your AgentOS profile${profileSuffix()}, " +
            "find **@${adapter.bot.username}** under Messaging, choose **Connect**, and follow what it says."

    private fun profileSuffix(): String = links.profile()?.let { " ($it)" } ?: ""

    private fun helpText(chat: Chat): String {
        val prefix = chat.adapter.commandPrefix
        return """
            You are talking to **${chat.agent.name}**: your messages go to the current case, and the agent answers here.

            ${prefix}new: start a new case with your next message
            ${prefix}cases: continue one of your recent cases here
            ${prefix}case: show the current case and its link
            ${prefix}help: show this help
            """.trimIndent()
    }

    private fun canWriteCase(
        user: User,
        caseId: UUID,
    ): Boolean = permissionService.hasPermission(user.id.toString(), EntityType.CASE, caseId.toString(), Action.WRITE)

    private val CaseDto.caseId: UUID get() = requireNotNull(id) { "A stored case has an id" }

    private val CaseDto.name: String get() = title ?: "Case $caseId"

    /** A linked sender who may use the bot's agent, in a chat, with the conversation of that chat. */
    private data class Chat(
        val adapter: MessagingAdapter,
        val chatId: String,
        val user: User,
        val agent: AgentConfig,
        /** The namespace of the bot: where its cases start, and the only one the chat works in. */
        val namespaceId: UUID,
        val conversation: MessagingConversation,
    )

    private sealed interface ChatAccess {
        data class Granted(
            val chat: Chat,
        ) : ChatAccess

        /** [text] answers a message, [notice] a click. */
        data class Refused(
            val text: String,
            val notice: String,
        ) : ChatAccess
    }

    /** A refusal told to the user as is. */
    private class MessagingRefusal(
        override val message: String,
    ) : RuntimeException(message)

    companion object : KLogging() {
        private const val MAX_LISTED_CASES = 8
        private const val MAX_LABEL_LENGTH = 48
        private const val MAX_EXCERPT_LENGTH = 1500

        /** Telegram shows at most 200 characters in the notice of a click. */
        private const val MAX_NOTICE_LENGTH = 190

        private const val CONTINUE_HINT = "Reply here to continue this case."
        private const val FAILURE_TEXT = "Something went wrong: your message did not reach AgentOS. Please try again."
        private const val NOT_LINKED_NOTICE = "Link your account to AgentOS first."
        private const val NO_AGENT_TEXT = "The agent of this bot no longer exists."
        private const val EXPIRED_BUTTON_NOTICE = "This button no longer works."
        private const val DENIED_TEXT = "AgentOS refused this: you do not have the rights for it. Ask an admin of the namespace."
        private const val DENIED_NOTICE = "AgentOS refused this: you do not have the rights for it."
        private const val PLAN_GONE_NOTICE = "This plan no longer exists."
        private const val SECONDS_PER_MINUTE = 60

        /** Marks a message the user spoke, in the chat and in the case. */
        const val VOICE_MARK = "🎤"
        const val VOICE_OFF_TEXT = "Voice messages are not transcribed on this AgentOS instance: please write your message."
        const val VOICE_FAILED_TEXT = "I could not transcribe this voice message: please try again, or write it."
        const val NO_WORDS_TEXT = "I could not make out any words in this voice message."
        const val UNSUPPORTED_TEXT = "I can only read text and voice messages: send yours as text, or as a voice message."
        const val UNSUPPORTED_NO_VOICE_TEXT = "I can only read text messages: please write yours."

        /** How [io.whozoss.agentos.configAgent.ConfigChangeService] names a refusal to someone the plan is not for. */
        private const val NOT_RECIPIENT = "NOT_RECIPIENT"
    }
}
