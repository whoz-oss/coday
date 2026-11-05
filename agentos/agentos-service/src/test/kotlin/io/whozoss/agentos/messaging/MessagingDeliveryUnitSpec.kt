package io.whozoss.agentos.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseEvent.CaseEventCreatedEvent
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.SessionContextKeys
import io.whozoss.agentos.configAgent.ConfigAgentIntegration
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeApi
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeState
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeViewDto
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.delay
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class MessagingDeliveryUnitSpec :
    StringSpec({

        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val ada = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "ada@example.com", firstname = "Ada")
        val user = Actor(id = ada.id.toString(), displayName = "Ada", role = ActorRole.USER)
        val now = Instant.parse("2026-11-05T10:00:00Z")
        val agent = Actor(id = UUID.randomUUID().toString(), displayName = "Coday", role = ActorRole.AGENT)

        fun registryOf(adapter: MessagingAdapter): MessagingBotRegistry =
            MessagingBotRegistry().also {
                it.put(adapter.bot, MessagingBotStatus.RUNNING)
                it.setAdapter(adapter.bot.id, adapter)
            }

        fun conversation(
            caseIdOfChat: UUID? = caseId,
            owner: UUID = ada.id,
        ) = MessagingConversation(platform = MessagingPlatform.TELEGRAM, accountId = "4242", chatId = "11", userId = owner, caseId = caseIdOfChat)

        fun delivery(
            adapter: FakeMessagingAdapter?,
            chat: MessagingConversation,
            caseService: CaseService = mockk(),
            configChanges: ConfigChangeApi = mockk(),
        ): MessagingDelivery {
            val conversations = mockk<MessagingConversationService>()
            every { conversations.findById(chat.id) } returns chat
            val users = mockk<UserService>()
            every { users.findById(ada.id) } returns ada
            return MessagingDelivery(
                registry = adapter?.let(::registryOf) ?: MessagingBotRegistry(),
                conversations = conversations,
                caseService = caseService,
                userService = users,
                configChanges = configChanges,
                links = MessagingLinks(MessagingProperties(publicUrl = "https://agentos.example.com")),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
        }

        fun userMessage(conversationId: UUID?) =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = user,
                content = listOf(MessageContent.Text("question")),
                sessionContext =
                    conversationId?.let {
                        mapOf(
                            SessionContextKeys.MESSAGING to
                                mapOf(SessionContextKeys.PLATFORM to "TELEGRAM", SessionContextKeys.CONVERSATION_ID to it.toString()),
                        )
                    },
            )

        fun agentMessage(text: String) =
            MessageEvent(namespaceId = namespaceId, caseId = caseId, actor = agent, content = listOf(MessageContent.Text(text)))

        fun MessagingDelivery.receive(vararg events: io.whozoss.agentos.sdk.caseEvent.CaseEvent) =
            events.forEach { onCaseEventCreated(CaseEventCreatedEvent(it)) }

        fun proposeResponse(questionId: UUID) =
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = "call-1",
                toolName = ConfigAgentIntegration.PROPOSE_TOOL_NAME,
                output = MessageContent.Text("proposed"),
                structuredOutput = ObjectMapper().createObjectNode().put("questionId", questionId.toString()),
            )

        fun planQuestion(questionId: UUID) =
            QuestionEvent(
                metadata = EntityMetadata(id = questionId),
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = UUID.randomUUID(),
                agentName = "Coday",
                question = "Review the plan in the AgentOS card, then apply or reject it.",
                userId = ada.id,
            )

        fun planView(questionId: UUID) =
            ConfigChangeViewDto(
                questionId = questionId,
                caseId = caseId,
                recipientUserId = ada.id,
                agentName = "Coday",
                summary = "Describe Assistant better.",
                planHash = "hash",
                createdAt = now,
                expiresAt = now.plusSeconds(30 * 60),
                state = ConfigChangeState.OPEN,
                canApply = true,
                canReject = true,
                operations = listOf(MessagingPlanCardsUnitSpec.updateDescription()),
                warnings = emptyList(),
            )

        "the answer to a message from the chat goes back to the chat" {
            val adapter = FakeMessagingAdapter()
            val chat = conversation()
            val delivery = delivery(adapter, chat)

            delivery.receive(userMessage(chat.id), agentMessage("the answer"))

            eventually(2.seconds) { adapter.textsTo("11") shouldBe listOf("the answer") }
            delivery.stop()
        }

        "the answer to a message typed in the UI stays in the UI" {
            val adapter = FakeMessagingAdapter()
            val chat = conversation()
            val delivery = delivery(adapter, chat)

            delivery.receive(userMessage(chat.id), userMessage(conversationId = null), agentMessage("the answer"))

            delay(300)
            adapter.sent.shouldBeEmpty()
            delivery.stop()
        }

        "a redirect sends what the case says next to the chat" {
            val adapter = FakeMessagingAdapter()
            val chat = conversation()
            val delivery = delivery(adapter, chat)

            delivery.redirect(caseId, chat.id)
            delivery.receive(agentMessage("still running"))

            eventually(2.seconds) { adapter.textsTo("11") shouldBe listOf("still running") }
            delivery.stop()
        }

        "an answer for a case the chat has left names that case" {
            val adapter = FakeMessagingAdapter()
            val chat = conversation(caseIdOfChat = UUID.randomUUID())
            val caseService = mockk<CaseService>()
            every { caseService.findById(caseId) } returns Case(namespaceId = namespaceId, title = "Refactoring")
            val delivery = delivery(adapter, chat, caseService)

            delivery.receive(userMessage(chat.id), agentMessage("done"))

            eventually(2.seconds) { adapter.textsTo("11") shouldBe listOf("📁 **Refactoring**\n\ndone") }
            delivery.stop()
        }

        "a question with options comes with one button per option, and a warning is sent as such" {
            val adapter = FakeMessagingAdapter()
            val chat = conversation()
            val delivery = delivery(adapter, chat)
            val question =
                QuestionEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = UUID.randomUUID(),
                    agentName = "Coday",
                    question = "Which one?",
                    options = listOf("A", "B"),
                    questionType = QuestionType.SINGLE_CHOICE,
                )

            delivery.receive(userMessage(chat.id), question, WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "No default agent"))

            eventually(2.seconds) {
                adapter.sent.map { it.message } shouldBe
                    listOf(
                        OutboundMessage(
                            "Which one?",
                            listOf(
                                MessagingButton("A", MessagingAction.AnswerQuestion(question.id, 0)),
                                MessagingButton("B", MessagingAction.AnswerQuestion(question.id, 1)),
                            ),
                        ),
                        OutboundMessage("⚠️ No default agent"),
                    )
            }
            delivery.stop()
        }

        "an authorization request comes as a link button" {
            val adapter = FakeMessagingAdapter()
            val chat = conversation()
            val delivery = delivery(adapter, chat)
            val question =
                QuestionEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = UUID.randomUUID(),
                    agentName = "Coday",
                    question = "https://auth.example.com/authorize?x=1",
                    questionType = QuestionType.OAUTH_AUTHORIZE,
                )

            delivery.receive(userMessage(chat.id), question)

            eventually(2.seconds) {
                adapter.sent.single().message.buttons shouldBe
                    listOf(MessagingButton("Authorize", MessagingAction.OpenUrl("https://auth.example.com/authorize?x=1")))
            }
            delivery.stop()
        }

        "a case no chat follows costs no lookup" {
            val conversations = mockk<MessagingConversationService>()
            val delivery =
                MessagingDelivery(MessagingBotRegistry(), conversations, mockk(), mockk(), mockk(), MessagingLinks(MessagingProperties()), Clock.systemUTC())

            delivery.receive(userMessage(null), agentMessage("the answer"))

            delay(200)
            io.mockk.verify(exactly = 0) { conversations.findById(any()) }
            delivery.stop()
        }

        "the answer for a bot that no longer runs is dropped" {
            val chat = conversation()
            val conversations = mockk<MessagingConversationService>()
            every { conversations.findById(chat.id) } returns chat
            val delivery =
                MessagingDelivery(MessagingBotRegistry(), conversations, mockk(), mockk(), mockk(), MessagingLinks(MessagingProperties()), Clock.systemUTC())

            delivery.receive(userMessage(chat.id), agentMessage("the answer"))

            eventually(2.seconds) { io.mockk.verify { conversations.findById(chat.id) } }
            delivery.stop()
        }

        "a message cannot send its case's answers to the chat of another user" {
            val adapter = FakeMessagingAdapter()
            // The session context comes from the client: it may name any conversation.
            val someoneElsesChat = conversation(owner = UUID.randomUUID())
            val delivery = delivery(adapter, someoneElsesChat)

            delivery.receive(userMessage(someoneElsesChat.id), agentMessage("the answer"))

            delay(300)
            adapter.sent.shouldBeEmpty()
            delivery.stop()
        }

        "a plan the agent proposes goes out as its card, read as the chat's user, with Apply and Reject" {
            val adapter = FakeMessagingAdapter()
            val chat = conversation()
            val questionId = UUID.randomUUID()
            val configChanges = mockk<ConfigChangeApi>()
            var readAs: String? = null
            every { configChanges.viewConfigChange(caseId, questionId) } answers {
                readAs = SecurityContextHolder.getContext().authentication?.name
                planView(questionId)
            }
            val delivery = delivery(adapter, chat, configChanges = configChanges)

            delivery.receive(userMessage(chat.id), proposeResponse(questionId), planQuestion(questionId))

            eventually(2.seconds) {
                val card = adapter.sent.single().message
                card.text shouldContain "Coday proposes a configuration change"
                card.text shouldContain "1. **Update agent Assistant**"
                card.text shouldContain "`/description`: `old` → `new`"
                card.text shouldContain "_Expires in 30 min._"
                card.buttons.map { it.action } shouldBe
                    listOf(
                        MessagingAction.ApplyPlan(questionId),
                        MessagingAction.RejectPlan(questionId),
                        MessagingAction.OpenUrl("https://agentos.example.com/home?ns=$namespaceId&case=$caseId"),
                    )
            }
            readAs shouldBe ada.id.toString()
            (SecurityContextHolder.getContext().authentication is AgentOsAuthentication) shouldBe false
            delivery.stop()
        }

        "a plan whose card cannot be read goes out as its question" {
            val adapter = FakeMessagingAdapter()
            val chat = conversation()
            val questionId = UUID.randomUUID()
            val configChanges = mockk<ConfigChangeApi>()
            every { configChanges.viewConfigChange(caseId, questionId) } throws org.springframework.security.access.AccessDeniedException("no")
            val delivery = delivery(adapter, chat, configChanges = configChanges)

            delivery.receive(userMessage(chat.id), proposeResponse(questionId), planQuestion(questionId))

            eventually(2.seconds) { adapter.textsTo("11") shouldBe listOf("Review the plan in the AgentOS card, then apply or reject it.") }
            delivery.stop()
        }
    })
