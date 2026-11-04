package io.whozoss.agentos.chat

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.compaction.ContextBudget
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The split of [UsageTrackingChatModel]: the tool loop and the context observation are always on, the cost
 * part (accumulator, cost gate) exists only when an accumulator is given, i.e. when usage is enabled.
 */
class UsageTrackingChatModelObservationSpec :
    StringSpec({
        timeout = 10_000

        val model = AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "test")

        fun response(
            tool: Boolean = false,
            text: String = if (tool) "" else "Done",
            usage: DefaultUsage? = null,
        ): ChatResponse {
            val message =
                AssistantMessage
                    .builder()
                    .content(text)
                    .toolCalls(if (tool) listOf(AssistantMessage.ToolCall("call-1", "function", "testTool", "{}")) else emptyList())
                    .build()
            val metadata = ChatResponseMetadata.builder()
            if (usage != null) metadata.usage(usage)
            return ChatResponse(listOf(Generation(message)), metadata.build())
        }

        /** OpenAI-shaped native usage: [promptTokens] is the size of the prompt the provider measured. */
        fun openAiUsage(promptTokens: Int) = DefaultUsage(promptTokens, 5, promptTokens + 5, OpenAiApi.Usage(5, promptTokens, promptTokens + 5, null, null))

        fun toolCallback(action: () -> String): ToolCallback =
            object : ToolCallback {
                override fun getToolDefinition(): ToolDefinition =
                    ToolDefinition
                        .builder()
                        .name("testTool")
                        .description("Test tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build()

                override fun call(input: String): String = action()
            }

        /** The result of one run of [tracked]: the answers, what the provider received, and what the tools did. */
        class Run(
            val responses: List<ChatResponse>,
            val prompts: List<Prompt>,
            val toolsExecuted: Int,
        ) {
            fun text() = responses.joinToString("") { it.result?.output?.text.orEmpty() }

            fun marker() = responses.last().metadata.get<Int>(UsageTrackingChatModel.MAX_ITERATIONS_REACHED)
        }

        /**
         * Runs [UsageTrackingChatModel] over a scripted provider. [reply] gets the request index and whether that
         * request is the summary asked at the limit.
         */
        fun tracked(
            streaming: Boolean,
            accumulator: UsageAccumulator? = null,
            budget: ContextBudget = ContextBudget.disabled(),
            apiType: AiApiType = AiApiType.OpenAI,
            maxToolRounds: Int = 2,
            reply: (index: Int, summaryRequested: Boolean) -> ChatResponse,
        ): Run {
            val toolsExecuted = AtomicInteger()
            val options = ToolCallingChatOptions.builder().toolCallbacks(toolCallback { "ok".also { toolsExecuted.incrementAndGet() } }).build()
            val prompts = CopyOnWriteArrayList<Prompt>()

            fun answer(prompt: Prompt): ChatResponse {
                val index = prompts.size
                prompts.add(prompt)
                val messages = prompt.instructions
                val summaryRequested = messages.last() is UserMessage && messages.getOrNull(messages.size - 2) is ToolResponseMessage
                return reply(index, summaryRequested)
            }
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns options
            every { raw.call(any<Prompt>()) } answers { answer(firstArg()) }
            every { raw.stream(any<Prompt>()) } answers { Flux.just(answer(firstArg())) }
            val wrapper =
                UsageTrackingChatModel(raw, accumulator, apiType, model, maxToolRounds = maxToolRounds, contextBudget = budget)
            val prompt = Prompt(listOf(UserMessage("Hello")), options)
            val responses =
                if (streaming) {
                    wrapper.stream(prompt).collectList().block(Duration.ofSeconds(3))!!
                } else {
                    listOf(wrapper.call(prompt))
                }
            return Run(responses, prompts, toolsExecuted.get())
        }

        // ---------------------------------------------------------------------
        // Usage disabled: no accumulator. The wrapper still owns the tool loop.
        // ---------------------------------------------------------------------

        listOf(false, true).forEach { streaming ->
            val mode = if (streaming) "stream" else "call"

            "$mode without accumulator: the wrapper executes the tools itself, internal execution is off" {
                val run = tracked(streaming) { index, _ -> if (index == 0) response(tool = true) else response(text = "Finished") }

                run.text() shouldBe "Finished"
                run.toolsExecuted shouldBe 1
                run.prompts.size shouldBe 2
                run.prompts.forEach { (it.options as ToolCallingChatOptions).internalToolExecutionEnabled shouldBe false }
            }

            "$mode without accumulator: tool calls past maxToolRounds are dropped, the model summarizes, the limit is marked" {
                val run =
                    tracked(streaming) { _, summaryRequested -> if (summaryRequested) response(text = "Summary") else response(tool = true) }

                run.text() shouldBe "Summary"
                run.marker() shouldBe 2
                run.toolsExecuted shouldBe 2
                run.prompts.size shouldBe 4
                (run.prompts[3].instructions.last() as UserMessage).text shouldContain "Do not call any tool"
            }

            "$mode without accumulator: an answer right after the last allowed round is not flagged" {
                val run = tracked(streaming) { index, _ -> if (index < 2) response(tool = true) else response(text = "Finished") }

                run.text() shouldBe "Finished"
                run.marker() shouldBe null
                run.toolsExecuted shouldBe 2
            }

            "$mode without accumulator: a summary that only calls a tool gets the fallback text" {
                val run = tracked(streaming) { _, _ -> response(tool = true) }

                run.text() shouldBe "I reached the maximum number of tool rounds (2) before finishing. Ask me to continue."
                run.marker() shouldBe 2
            }
        }

        // ---------------------------------------------------------------------
        // Usage enabled: the cost part is unchanged next to the observation
        // ---------------------------------------------------------------------

        listOf(false, true).forEach { streaming ->
            "${if (streaming) "stream" else "call"} with accumulator and budget: usage is still recorded, once per request" {
                val accumulator = UsageAccumulator()
                val budget = ContextBudget(contextWindow = 1_000_000)

                tracked(streaming, accumulator, budget) { index, _ ->
                    if (index == 0) response(tool = true, usage = openAiUsage(10)) else response(text = "Done", usage = openAiUsage(20))
                }

                accumulator.snapshot().calls shouldBe 2L
                accumulator.total.inputTokens shouldBe 30L
                accumulator.failed shouldBe false
            }
        }

        // ---------------------------------------------------------------------
        // Observation: native usage when present, estimate otherwise
        // ---------------------------------------------------------------------

        listOf(false, true).forEach { streaming ->
            val mode = if (streaming) "stream" else "call"

            "$mode: the budget records the native prompt size when the response reports it, with or without usage tracking" {
                listOf<UsageAccumulator?>(null, UsageAccumulator()).forEach { accumulator ->
                    val budget = ContextBudget(contextWindow = 1_000_000)

                    tracked(streaming, accumulator, budget) { _, _ -> response(text = "Done", usage = openAiUsage(77)) }

                    budget.snapshot().lastObservedTokens shouldBe 77L
                    // The prompt is the message plus the tool definition: 55 chars, 19 tokens at 3 chars per token.
                    budget.snapshot().lastEstimatedTokens shouldBe 19L
                }
            }

            "$mode: the budget works from the estimate alone when the response carries no usage" {
                val budget = ContextBudget(contextWindow = 1_000_000)

                tracked(streaming, accumulator = null, budget = budget) { _, _ -> response(text = "Done") }

                budget.snapshot().lastObservedTokens shouldBe null
                budget.snapshot().charsPerToken shouldBe ContextBudget.INITIAL_CHARS_PER_TOKEN
                ((budget.snapshot().lastEstimatedTokens ?: 0L) > 0L) shouldBe true
            }

            "$mode: a provider whose native usage is unreadable falls back to the generic prompt count" {
                val budget = ContextBudget(contextWindow = 1_000_000)

                tracked(streaming, accumulator = null, budget = budget, apiType = AiApiType.Gemini) { _, _ ->
                    response(text = "Done", usage = DefaultUsage(42, 8, 50))
                }

                budget.snapshot().lastObservedTokens shouldBe 42L
            }

            "$mode: each request of the tool loop is observed and corrects the next estimate" {
                val budget = ContextBudget(contextWindow = 1_000_000)

                tracked(streaming, accumulator = null, budget = budget) { index, _ ->
                    if (index == 0) response(tool = true, usage = openAiUsage(10)) else response(text = "Done", usage = openAiUsage(500))
                }

                // Last observation wins: the second prompt, measured at 500 tokens, sets the ratio.
                budget.snapshot().lastObservedTokens shouldBe 500L
            }

            "$mode: observing changes nothing about what the provider receives" {
                fun script(budget: ContextBudget) =
                    tracked(streaming, accumulator = null, budget = budget) { index, _ ->
                        if (index == 0) response(tool = true, usage = openAiUsage(10)) else response(text = "Done")
                    }

                val observed = script(ContextBudget(contextWindow = 10))
                val unobserved = script(ContextBudget.disabled())

                observed.prompts.map { it.instructions.map { m -> m.text } } shouldBe unobserved.prompts.map { it.instructions.map { m -> m.text } }
                observed.text() shouldBe unobserved.text()
                observed.toolsExecuted shouldBe unobserved.toolsExecuted
            }
        }

        "a disabled budget observes nothing" {
            val budget = ContextBudget.disabled()

            tracked(streaming = false, accumulator = null, budget = budget) { _, _ -> response(text = "Done", usage = openAiUsage(77)) }

            budget.snapshot().lastObservedTokens shouldBe null
            budget.snapshot().lastEstimatedTokens shouldBe null
        }

        // ---------------------------------------------------------------------
        // Reporting: WARN once per invocation, DEBUG on every request
        // ---------------------------------------------------------------------

        /** Runs [block] with the wrapper's logger at DEBUG, and returns what it logged. */
        fun logged(block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(UsageTrackingChatModel::class.java) as Logger
            val previous = logger.level
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.level = Level.DEBUG
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                logger.level = previous
                appender.stop()
            }
            return appender.list.filter { it.formattedMessage.contains("[ContextBudget]") }
        }

        listOf(false, true).forEach { streaming ->
            val mode = if (streaming) "stream" else "call"

            "$mode: reaching the trigger logs ONE warning per invocation and a debug line per request" {
                val budget = ContextBudget(contextWindow = 10, label = "case=c1 agent=a1 model=m1")

                val lines = logged { tracked(streaming, accumulator = null, budget = budget) { index, _ -> if (index < 2) response(tool = true) else response(text = "Done") } }

                val warnings = lines.filter { it.level == Level.WARN }
                warnings.size shouldBe 1
                warnings.single().formattedMessage shouldContain "case=c1 agent=a1 model=m1"
                warnings.single().formattedMessage shouldContain ">= trigger 8 of usable 10"
                warnings.single().formattedMessage shouldContain "observed="
                warnings.single().formattedMessage shouldContain "ratio="
                lines.count { it.level == Level.DEBUG } shouldBe 3
            }

            "$mode: a prompt that stays under the trigger logs debug lines and no warning" {
                val budget = ContextBudget(contextWindow = 1_000_000)

                val lines = logged { tracked(streaming, accumulator = null, budget = budget) { index, _ -> if (index == 0) response(tool = true) else response(text = "Done") } }

                lines.count { it.level == Level.WARN } shouldBe 0
                lines.count { it.level == Level.DEBUG } shouldBe 2
            }

            "$mode: without a context window nothing is logged on the budget's behalf" {
                val lines = logged { tracked(streaming, accumulator = null, budget = ContextBudget.disabled()) { index, _ -> if (index == 0) response(tool = true) else response(text = "Done") } }

                lines shouldBe emptyList()
            }
        }

        "the warning is reported once per budget, so two invocations report once each" {
            val lines =
                logged {
                    repeat(2) {
                        tracked(streaming = false, accumulator = null, budget = ContextBudget(contextWindow = 10)) { _, _ -> response(text = "Done") }
                    }
                }

            lines.count { it.level == Level.WARN } shouldBe 2
        }

        "a failing measure never fails the run" {
            // The tool definition cannot be read, so measuring the prompt throws: the request must go through anyway.
            val broken =
                object : ToolCallback {
                    override fun getToolDefinition(): ToolDefinition = throw IllegalStateException("boom")

                    override fun call(input: String): String = ""
                }
            val options = ToolCallingChatOptions.builder().toolCallbacks(broken).build()
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns options
            every { raw.call(any<Prompt>()) } returns response(text = "Done")
            val budget = ContextBudget(contextWindow = 1_000_000)

            val result =
                UsageTrackingChatModel(raw, null, AiApiType.OpenAI, model, contextBudget = budget)
                    .call(Prompt(listOf(UserMessage("Hello")), options))

            result.result.output.text shouldBe "Done"
            budget.snapshot().lastEstimatedTokens shouldBe null
        }
    })
