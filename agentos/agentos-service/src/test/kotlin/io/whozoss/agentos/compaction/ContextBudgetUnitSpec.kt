package io.whozoss.agentos.compaction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContextBudgetUnitSpec :
    StringSpec({
        timeout = 10_000

        fun prompt(chars: Int) = Prompt(listOf(UserMessage("x".repeat(chars))))

        fun toolCallback(
            name: String,
            description: String,
            schema: String,
        ): ToolCallback =
            object : ToolCallback {
                override fun getToolDefinition(): ToolDefinition =
                    ToolDefinition
                        .builder()
                        .name(name)
                        .description(description)
                        .inputSchema(schema)
                        .build()

                override fun call(input: String): String = ""
            }

        // ---------------------------------------------------------------------
        // Disabled
        // ---------------------------------------------------------------------

        "a null context window disables the budget: never reached, no usable size, no trigger, no target" {
            val budget = ContextBudget(contextWindow = null, maxCompletionTokens = 4_000)

            budget.enabled shouldBe false
            budget.usableTokens shouldBe null
            budget.triggerTokens shouldBe null
            budget.targetTokens shouldBe null
            budget.isTriggerReached(0) shouldBe false
            budget.isTriggerReached(Long.MAX_VALUE) shouldBe false
        }

        "a window that does not exceed the completion allowance leaves nothing to use, so the budget is disabled" {
            ContextBudget(contextWindow = 4_000, maxCompletionTokens = 4_000).enabled shouldBe false
            ContextBudget(contextWindow = 4_000, maxCompletionTokens = 8_000).enabled shouldBe false
        }

        "the disabled factory is a budget that is never reached" {
            val budget = ContextBudget.disabled()

            budget.enabled shouldBe false
            budget.isTriggerReached(Long.MAX_VALUE) shouldBe false
        }

        // ---------------------------------------------------------------------
        // Usable window, trigger and target
        // ---------------------------------------------------------------------

        "the usable window is the context window minus the completion allowance" {
            ContextBudget(contextWindow = 200_000, maxCompletionTokens = 8_000).usableTokens shouldBe 192_000L
        }

        "without a completion allowance the whole window is usable" {
            ContextBudget(contextWindow = 200_000).usableTokens shouldBe 200_000L
        }

        "trigger and target are the configured fractions of the usable window" {
            val budget = ContextBudget(contextWindow = 1_000, maxCompletionTokens = 200, threshold = 0.8, target = 0.5)

            budget.usableTokens shouldBe 800L
            budget.triggerTokens shouldBe 640L
            budget.targetTokens shouldBe 400L
        }

        "the trigger is reached at the trigger itself and not one token before" {
            val budget = ContextBudget(contextWindow = 1_000, maxCompletionTokens = 200)

            budget.isTriggerReached(639) shouldBe false
            budget.isTriggerReached(640) shouldBe true
            budget.isTriggerReached(5_000) shouldBe true
        }

        "the default fractions are 0.8 and 0.5" {
            val budget = ContextBudget(contextWindow = 1_000)

            budget.threshold shouldBe 0.8
            budget.target shouldBe 0.5
        }

        "fractions must satisfy 0 < target < threshold <= 1" {
            listOf(
                0.0 to 0.8,
                -0.1 to 0.8,
                0.8 to 0.8,
                0.9 to 0.8,
                0.5 to 1.1,
            ).forEach { (target, threshold) ->
                shouldThrow<IllegalArgumentException> { ContextBudget(contextWindow = 1_000, threshold = threshold, target = target) }
            }
            ContextBudget(contextWindow = 1_000, threshold = 1.0, target = 0.5).triggerTokens shouldBe 1_000L
        }

        // ---------------------------------------------------------------------
        // Estimate
        // ---------------------------------------------------------------------

        "a prompt is estimated at 3 characters per token, rounded up" {
            val budget = ContextBudget(contextWindow = 1_000_000)

            budget.estimate(prompt(300)) shouldBe 100L
            budget.estimate(prompt(301)) shouldBe 101L
            budget.estimate(prompt(1)) shouldBe 1L
        }

        "every message of the prompt counts, whatever its role" {
            val budget = ContextBudget(contextWindow = 1_000_000)
            val full =
                Prompt(
                    listOf(
                        SystemMessage("s".repeat(30)),
                        UserMessage("u".repeat(30)),
                        AssistantMessage("a".repeat(30)),
                    ),
                )

            budget.promptChars(full) shouldBe 90L
            budget.estimate(full) shouldBe 30L
        }

        "tool-call arguments and names count" {
            val budget = ContextBudget(contextWindow = 1_000_000)
            val toolCall = AssistantMessage.ToolCall("call-1", "function", "readFile", """{"path":"/a/b"}""")
            val assistant = AssistantMessage.builder().content("").toolCalls(listOf(toolCall)).build()

            budget.promptChars(Prompt(listOf(assistant))) shouldBe ("readFile".length + """{"path":"/a/b"}""".length).toLong()
        }

        "tool responses count" {
            val budget = ContextBudget(contextWindow = 1_000_000)
            val response =
                ToolResponseMessage
                    .builder()
                    .responses(listOf(ToolResponseMessage.ToolResponse("call-1", "readFile", "y".repeat(500))))
                    .build()

            budget.promptChars(Prompt(listOf(response))) shouldBe ("readFile".length + 500).toLong()
        }

        "tool definitions count: name, description and input schema" {
            val budget = ContextBudget(contextWindow = 1_000_000)
            val options =
                ToolCallingChatOptions
                    .builder()
                    .toolCallbacks(
                        toolCallback("readFile", "Reads a file", """{"type":"object"}"""),
                        toolCallback("edit", "Edits", "{}"),
                    ).build()
            val withTools = Prompt(listOf(UserMessage("hello")), options)

            val toolChars = ("readFile".length + "Reads a file".length + """{"type":"object"}""".length) + ("edit".length + "Edits".length + 2)
            budget.promptChars(withTools) shouldBe (5 + toolChars).toLong()
        }

        "a prompt without options, or with options that declare no tools, adds nothing for tools" {
            val budget = ContextBudget(contextWindow = 1_000_000)

            budget.promptChars(Prompt(listOf(UserMessage("hello")))) shouldBe 5L
            budget.promptChars(Prompt(listOf(UserMessage("hello")), ToolCallingChatOptions.builder().build())) shouldBe 5L
        }

        "the estimate is compared to the usable window directly: no fixed overhead is subtracted" {
            val budget = ContextBudget(contextWindow = 1_000, maxCompletionTokens = 200)
            val options =
                ToolCallingChatOptions
                    .builder()
                    .toolCallbacks(toolCallback("t", "d".repeat(300), "{}"))
                    .build()
            // 1 + 300 + 2 = 303 chars of tool definition, 300 of message: 603 chars = 201 tokens.
            val estimated = budget.estimate(Prompt(listOf(UserMessage("u".repeat(300))), options))

            estimated shouldBe 201L
            budget.isTriggerReached(estimated) shouldBe false
            budget.isTriggerReached(budget.estimate(prompt(1_920))) shouldBe true
        }

        // ---------------------------------------------------------------------
        // Correction
        // ---------------------------------------------------------------------

        "a measured prompt size replaces the 3 chars per token for later estimates" {
            val budget = ContextBudget(contextWindow = 1_000_000)

            budget.record(promptChars = 400, observedPromptTokens = 100)

            budget.snapshot().charsPerToken shouldBe (4.0 plusOrMinus 1e-9)
            budget.estimateTokens(800) shouldBe 200L
        }

        "the last observation wins" {
            val budget = ContextBudget(contextWindow = 1_000_000)

            budget.record(promptChars = 400, observedPromptTokens = 100)
            budget.record(promptChars = 500, observedPromptTokens = 250)

            budget.snapshot().charsPerToken shouldBe (2.0 plusOrMinus 1e-9)
            budget.snapshot().lastObservedTokens shouldBe 250L
        }

        "an observation that tells nothing is ignored" {
            val budget = ContextBudget(contextWindow = 1_000_000)
            budget.record(promptChars = 400, observedPromptTokens = 100)

            budget.record(promptChars = 0, observedPromptTokens = 100)
            budget.record(promptChars = 400, observedPromptTokens = 0)
            budget.record(promptChars = -5, observedPromptTokens = -5)

            budget.snapshot().charsPerToken shouldBe (4.0 plusOrMinus 1e-9)
            budget.snapshot().lastObservedTokens shouldBe 100L
        }

        "a degenerate observation is bounded instead of trusted" {
            val budget = ContextBudget(contextWindow = 1_000_000)

            // Images: very few characters for a lot of tokens.
            budget.record(promptChars = 10, observedPromptTokens = 100_000)
            budget.snapshot().charsPerToken shouldBe ContextBudget.MIN_CHARS_PER_TOKEN

            // Truncated usage: a lot of characters for almost no tokens.
            budget.record(promptChars = 1_000_000, observedPromptTokens = 10)
            budget.snapshot().charsPerToken shouldBe ContextBudget.MAX_CHARS_PER_TOKEN
        }

        "the correction is local to the invocation: another budget starts again at 3" {
            val first = ContextBudget(contextWindow = 1_000_000)
            val second = ContextBudget(contextWindow = 1_000_000)

            first.record(promptChars = 400, observedPromptTokens = 100)

            second.snapshot().charsPerToken shouldBe ContextBudget.INITIAL_CHARS_PER_TOKEN
        }

        "a corrected ratio moves the trigger: the same characters reach it sooner when tokens are denser" {
            val budget = ContextBudget(contextWindow = 1_000, threshold = 0.8) // trigger 800 tokens
            val chars = 2_000L

            budget.isTriggerReached(budget.estimateTokens(chars)) shouldBe false // 667 tokens at 3 chars per token
            budget.record(promptChars = 1_000, observedPromptTokens = 500) // 2 chars per token
            budget.isTriggerReached(budget.estimateTokens(chars)) shouldBe true // 1000 tokens
        }

        // ---------------------------------------------------------------------
        // Snapshot and one-shot report
        // ---------------------------------------------------------------------

        "the snapshot tells the last estimate, the last observation, the ratio and the usable window" {
            val budget = ContextBudget(contextWindow = 10_000, maxCompletionTokens = 1_000)
            budget.snapshot().lastEstimatedTokens shouldBe null
            budget.snapshot().lastObservedTokens shouldBe null

            budget.estimate(prompt(300))
            budget.record(promptChars = 300, observedPromptTokens = 60)

            with(budget.snapshot()) {
                usableTokens shouldBe 9_000L
                lastEstimatedTokens shouldBe 100L
                lastObservedTokens shouldBe 60L
                charsPerToken shouldBe (5.0 plusOrMinus 1e-9)
            }
        }

        "the first report of the trigger is granted once, whoever asks" {
            val budget = ContextBudget(contextWindow = 1_000)

            budget.markTriggerReported() shouldBe true
            budget.markTriggerReported() shouldBe false
            budget.markTriggerReported() shouldBe false
        }

        // ---------------------------------------------------------------------
        // Thread safety
        // ---------------------------------------------------------------------

        "concurrent observations never corrupt the state, and exactly one caller gets the report" {
            val budget = ContextBudget(contextWindow = 1_000_000)
            val threads = 8
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val reports = java.util.concurrent.atomic.AtomicInteger()
            try {
                val done =
                    (1..threads).map { index ->
                        pool.submit {
                            start.await()
                            repeat(500) {
                                budget.record(promptChars = 100L * index, observedPromptTokens = 50)
                                budget.estimate(prompt(300))
                            }
                            if (budget.markTriggerReported()) reports.incrementAndGet()
                        }
                    }
                start.countDown()
                done.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            reports.get() shouldBe 1
            // The ratio is one of the observations (2, 4, ..., 16 bounded to 8), never a blend of two.
            val possible = (1..threads).map { (100.0 * it / 50).coerceIn(ContextBudget.MIN_CHARS_PER_TOKEN, ContextBudget.MAX_CHARS_PER_TOKEN) }
            (budget.snapshot().charsPerToken in possible) shouldBe true
            budget.snapshot().lastObservedTokens shouldBe 50L
        }
    })
