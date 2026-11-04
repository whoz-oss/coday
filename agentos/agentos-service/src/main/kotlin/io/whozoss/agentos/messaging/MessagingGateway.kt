package io.whozoss.agentos.messaging

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.AgentMention
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.SessionContextKeys
import io.whozoss.agentos.context.UserSessionContextResolver
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateBroadcaster
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Takes in what the bot of an agent receives and turns it into case messages to that agent, as the user the
 * account is linked to.
 *
 * A bot is one agent's ([MessagingBot.agentId]), in one namespace: talking to the bot is talking to the agent,
 * and the cases it starts belong to that namespace. A direct chat holds one conversation ([MessagingConversation])
 * whose messages go to one case at a time: the first message starts a case, the next ones follow it, and `/new`,
 * `/cases` or a "Continue on Telegram" request from the UI move the chat to another case. The case is created
 * and fed exactly as [io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor] does, with the agent named
 * at the start of the message (`@agent`) when the case would otherwise go to another one: the agent runs as the
 * linked user, with that user's rights and credentials. Only a user who may use the agent in its namespace is
 * served. What the gateway does for a chat runs with that user authenticated, as a request of theirs would, so
 * that the secured services apply their own rules.
 *
 * Every message carries its origin in its session context ([SessionContextKeys.MESSAGING]), which is how
 * [MessagingDelivery] knows to send the reply back to the chat.
 *
 * Group chats are not handled yet: their messages are ignored.
 */
@Service
class MessagingGateway(
    private val identities: MessagingIdentityService,
    private val conversations: MessagingConversationService,
    private val caseService: CaseService,
    private val caseEventService: CaseEventService,
    private val userService: UserService,
    private val agentConfigService: AgentConfigService,
    private val bots: MessagingBotService,
    private val permissionService: PermissionService,
    private val caseListUpdateBroadcaster: NamespaceCaseListUpdateBroadcaster,
    private val sessionContextResolver: UserSessionContextResolver,
    private val delivery: MessagingDelivery,
    private val registry: MessagingBotRegistry,
    private val links: MessagingLinks,
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
                inbound is MessagingInbound.Click -> handleClick(adapter, inbound)
                inbound is MessagingInbound.BotManaged -> logger.debug { "[Messaging] @${adapter.bot.username} manages no bot" }
            }
        } catch (e: Exception) {
            logger.warn(e) { "[Messaging] failed to handle a ${adapter.platform} update from chat ${inbound.chatId}" }
            runCatching { adapter.send(inbound.chatId, OutboundMessage(FAILURE_TEXT)) }
        }
    }

    /**
     * Moves [user]'s direct chat with the bot [botId] to [caseId] and posts the case's last answer there, so that
     * the user continues the case from the chat, with the bot's agent.
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
        val case = caseService.findById(caseId) ?: throw ResourceNotFoundException("Case not found: $caseId")
        if (case.namespaceId != bot.namespaceId) throw ConflictException("@${bot.username} answers in another namespace than this case")
        val identity =
            identities.findByUser(user.id, bot.platform, bot.accountId)
                ?: throw ConflictException("Link your ${bot.platform} account to @${bot.username} first")
        val conversation =
            conversations
                .open(bot.platform, bot.accountId, identity.directChatId, user.id)
                .let { opened -> conversations.update(opened) { it.copy(caseId = case.id) } }
        delivery.redirect(case.id, conversation.id)
        adapter.send(identity.directChatId, OutboundMessage(caseSummary(case, CONTINUE_HINT)))
        logger.info { "[Messaging] case ${case.id} continued with @${bot.username} by user ${user.id}" }
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
            command?.name == MessagingCommand.START && !command.argument.isNullOrBlank() ->
                link(adapter, inbound, command.argument)
            else ->
                withChat(adapter, inbound.chatId, inbound.sender) { chat ->
                    when {
                        chat.conversation.lastInboundMessageId?.let { inbound.messageId <= it } == true ->
                            logger.debug { "[Messaging] message ${inbound.messageId} of ${inbound.chatId} already taken in" }
                        else -> {
                            val recorded =
                                conversations.update(chat.conversation) { it.copy(lastInboundMessageId = inbound.messageId) }
                            val current = chat.copy(conversation = recorded)
                            when (command?.name) {
                                MessagingCommand.START, MessagingCommand.HELP -> reply(current, helpText(current.agent))
                                MessagingCommand.NEW -> startNewCase(current)
                                MessagingCommand.CASE -> showCurrentCase(current)
                                MessagingCommand.CASES -> listCases(current)
                                else -> post(current, inbound.text)
                            }
                        }
                    }
                }
        }
    }

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
                "Your account is now linked to AgentOS as **$name**. Write to talk to $agent. /help lists the commands."
            } ?: "This link has expired or was already used. Ask for a new one in your AgentOS profile${profileSuffix()}."
        adapter.send(inbound.chatId, OutboundMessage(text))
    }

    /** Posts [text] to the chat's case, starting one when the chat has none it can still write to. */
    private fun post(
        chat: Chat,
        text: String,
    ) {
        val current = chat.conversation.caseId?.let { caseService.findById(it) }?.takeIf { canWriteCase(chat.user, it) }
        when {
            current != null && !current.status.isTerminal() -> {
                addMessage(chat, current, addressed(chat, current, text))
                chat.adapter.showTyping(chat.chatId)
            }
            else -> {
                if (current != null) reply(chat, "Your previous case is closed: this message starts a new one.")
                startCaseWith(chat, text)
            }
        }
    }

    private fun startCaseWith(
        chat: Chat,
        text: String,
    ) {
        val case = caseService.create(Case(namespaceId = chat.namespaceId))
        try {
            permissionService.grantPermission(
                userId = chat.user.id.toString(),
                entityType = EntityType.CASE,
                entityId = case.id.toString(),
                relation = PermissionRelation.ADMIN,
            )
            caseListUpdateBroadcaster.publishCaseCreated(case.namespaceId)
            conversations.update(chat.conversation) { it.copy(caseId = case.id) }
            addMessage(chat, case, addressed(chat, null, text))
        } catch (e: Exception) {
            // The failure itself is logged by handle(); this only records the compensation.
            logger.warn { "[Messaging] starting case ${case.id} from chat ${chat.chatId} failed, deleting it" }
            runCatching { caseService.delete(case.id) }
                .onFailure { logger.warn(it) { "[Messaging] could not delete the half-started case ${case.id}" } }
            throw e
        }
        chat.adapter.showTyping(chat.chatId)
        logger.info { "[Messaging] case ${case.id} started with @${chat.adapter.bot.username} by user ${chat.user.id}" }
    }

    /**
     * [text], addressed to the bot's agent when the case would otherwise go to another one: a message naming an
     * agent goes to that one, and a case whose last agent is the bot's needs no name (the case keeps its agent).
     */
    private fun addressed(
        chat: Chat,
        case: Case?,
        text: String,
    ): String {
        // A selection names its agent: its id is derived from that name, not the agent's own.
        val lastAgentName =
            case?.let { caseEventService.findByParent(it.id).filterIsInstance<AgentSelectedEvent>().lastOrNull()?.agentName }
        return when {
            AgentMention.extractName(text) != null || lastAgentName.equals(chat.agent.name, ignoreCase = true) -> text
            else -> "@${chat.agent.name} $text"
        }
    }

    private fun addMessage(
        chat: Chat,
        case: Case,
        text: String,
        answerToEventId: UUID? = null,
    ) {
        caseService.addMessage(
            caseId = case.id,
            actor = Actor(id = chat.user.id.toString(), displayName = chat.user.displayName(), role = ActorRole.USER),
            content = listOf(MessageContent.Text(text)),
            answerToEventId = answerToEventId,
            sessionContext = sessionContext(chat, case.namespaceId),
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
        val case = chat.conversation.caseId?.let { caseService.findById(it) }?.takeIf { canWriteCase(chat.user, it) }
        val text =
            when (case) {
                null -> "No case yet: your next message starts one with **${chat.agent.name}**."
                else -> caseSummary(case, "Status: ${case.status.name.lowercase()}.")
            }
        reply(chat, text)
    }

    /** The user's recent cases of the bot's namespace: the agent answers there only. */
    private fun listCases(chat: Chat) {
        val cases =
            caseService
                .findConcerningUser(chat.user.id)
                .filter { it.parentCaseId == null && it.namespaceId == chat.namespaceId }
        val lastMessageAt = caseEventService.findLastMessageTimestamps(cases.map { it.id })
        val recent = cases.sortedByDescending { lastMessageAt[it.id] ?: it.metadata.modified }.take(MAX_LISTED_CASES)
        val message =
            when {
                recent.isEmpty() -> OutboundMessage("You have no case here yet: send a message to start one.")
                else ->
                    OutboundMessage(
                        text = "Your recent cases. Choose the one to continue here:",
                        buttons =
                            recent.map { case ->
                                val marker = if (case.id == chat.conversation.caseId) "• " else ""
                                MessagingButton(
                                    label = marker + case.title.take(MAX_LABEL_LENGTH),
                                    action = MessagingAction.OpenCase(case.id),
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
                                is MessagingAction.OpenUrl, null -> EXPIRED_BUTTON_NOTICE
                            }
                        }
                    is ChatAccess.Refused -> access.notice
                }
            } catch (e: MessagingRefusal) {
                e.message
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
        val case =
            caseService.findById(question.caseId)?.takeIf { canWriteCase(chat.user, it) }
                ?: throw MessagingRefusal("You can no longer answer in this case.")
        val option =
            question.options?.getOrNull(action.optionIndex)
                ?: throw MessagingRefusal("This choice no longer exists.")
        when {
            question.userId != null && question.userId != chat.user.id ->
                throw MessagingRefusal("This question is for someone else.")
            caseEventService.findByParent(case.id).any { it is AnswerEvent && it.questionId == question.id } ->
                throw MessagingRefusal("This question was already answered.")
        }
        // An answer carries no session context: the reply follows the chat it was given in, from before the run.
        delivery.redirect(case.id, chat.conversation.id)
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
            caseService.findById(caseId)?.takeIf { canWriteCase(chat.user, it) && it.namespaceId == chat.namespaceId }
                ?: throw MessagingRefusal("You can no longer write in this case.")
        conversations.update(chat.conversation) { it.copy(caseId = case.id) }
        delivery.redirect(case.id, chat.conversation.id)
        reply(chat, caseSummary(case, CONTINUE_HINT))
        return "✓ ${case.title}"
    }

    // ======================================================
    // Helpers
    // ======================================================

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

    /**
     * Runs [block] with [user] authenticated on this thread, as
     * [io.whozoss.agentos.security.declarative.AgentOsAuthenticationFilter] does for a request, and puts the
     * previous context back after. The chat's lane runs [block] on one thread
     * from start to end: the gateway's calls block, they never suspend.
     */
    private fun <T> asUser(
        user: User,
        block: () -> T,
    ): T {
        val previous = SecurityContextHolder.getContext()
        val context = SecurityContextHolder.createEmptyContext().also { it.authentication = AgentOsAuthentication(user) }
        SecurityContextHolder.setContext(context)
        return try {
            block()
        } finally {
            SecurityContextHolder.setContext(previous)
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
        case: Case,
        hint: String,
    ): String {
        val lastAnswer =
            caseEventService
                .findByParent(case.id)
                .lastOrNull { it is MessageEvent && it.actor.role == ActorRole.AGENT }
                ?.let { message ->
                    (message as MessageEvent).content.filterIsInstance<MessageContent.Text>().joinToString("\n") { it.content }
                }
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.length > MAX_EXCERPT_LENGTH) it.take(MAX_EXCERPT_LENGTH) + "…" else it }
        return listOfNotNull(
            "📁 **${case.title}**",
            lastAnswer,
            hint,
            links.case(case.namespaceId, case.id)?.let { "[Open in AgentOS]($it)" },
        ).joinToString("\n\n")
    }

    private fun reply(
        chat: Chat,
        text: String,
    ) = chat.adapter.send(chat.chatId, OutboundMessage(text))

    private fun notLinkedText(adapter: MessagingAdapter): String =
        "This account is not linked to AgentOS yet. Open your AgentOS profile${profileSuffix()}, " +
            "find **@${adapter.bot.username}** under Messaging, choose **Connect**, and open the link it gives you."

    private fun profileSuffix(): String = links.profile()?.let { " ($it)" } ?: ""

    private fun helpText(agent: AgentConfig): String =
        """
        You are talking to **${agent.name}**: your messages go to the current case, and the agent answers here.

        /new: start a new case with your next message
        /cases: continue one of your recent cases here
        /case: show the current case and its link
        /help: show this help
        """.trimIndent()

    private fun canWriteCase(
        user: User,
        case: Case,
    ): Boolean = permissionService.hasPermission(user.id.toString(), EntityType.CASE, case.id.toString(), Action.WRITE)

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
    }
}
