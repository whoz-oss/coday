package io.whozoss.agentos.queryUser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

private val CONTEXT = mockk<ToolContext>(relaxed = true)

class QueryUserToolSpec : StringSpec({

    // -------------------------------------------------------------------------
    // Error cases: null input and blank question
    // -------------------------------------------------------------------------

    "execute returns a human-readable error when input is null" {
        val tool = QueryUserTool()
        val result = tool.execute(null, CONTEXT)
        result.success shouldBe false
        result.output shouldBe "A question is required."
    }

    "execute returns a human-readable error when question is blank" {
        val tool = QueryUserTool()
        val result = tool.execute(QueryUserTool.Input(question = "   "), CONTEXT)
        result.success shouldBe false
        result.output shouldBe "A question is required."
    }

    "execute returns a human-readable error when question is empty" {
        val tool = QueryUserTool()
        val result = tool.execute(QueryUserTool.Input(question = ""), CONTEXT)
        result.success shouldBe false
        result.output shouldBe "A question is required."
    }

    // -------------------------------------------------------------------------
    // Happy path: QuestionType derivation
    // -------------------------------------------------------------------------

    "execute throws AwaitAnswer with FREE_TEXT when options is null" {
        val tool = QueryUserTool()
        val ex = shouldThrow<AgentInterrupt.AwaitAnswer> {
            tool.execute(QueryUserTool.Input(question = "What is your preference?"), CONTEXT)
        }
        ex.question shouldBe "What is your preference?"
        ex.questionType shouldBe QuestionType.FREE_TEXT
        ex.options shouldBe null
    }

    "execute throws AwaitAnswer with FREE_TEXT when options is empty" {
        val tool = QueryUserTool()
        val ex = shouldThrow<AgentInterrupt.AwaitAnswer> {
            tool.execute(
                QueryUserTool.Input(question = "What is your preference?", options = emptyList()),
                CONTEXT,
            )
        }
        ex.questionType shouldBe QuestionType.FREE_TEXT
        ex.options shouldBe null // empty list normalised to null
    }

    "execute throws AwaitAnswer with SINGLE_CHOICE when options non-empty and allowCustomAnswer false" {
        val tool = QueryUserTool()
        val ex = shouldThrow<AgentInterrupt.AwaitAnswer> {
            tool.execute(
                QueryUserTool.Input(
                    question = "Pick one",
                    options = listOf("A", "B"),
                    allowCustomAnswer = false,
                ),
                CONTEXT,
            )
        }
        ex.question shouldBe "Pick one"
        ex.questionType shouldBe QuestionType.SINGLE_CHOICE
        ex.options shouldBe listOf("A", "B")
    }

    "execute throws AwaitAnswer with OPEN_CHOICE when options non-empty and allowCustomAnswer true" {
        val tool = QueryUserTool()
        val ex = shouldThrow<AgentInterrupt.AwaitAnswer> {
            tool.execute(
                QueryUserTool.Input(
                    question = "Pick or type",
                    options = listOf("X", "Y", "Z"),
                    allowCustomAnswer = true,
                ),
                CONTEXT,
            )
        }
        ex.question shouldBe "Pick or type"
        ex.questionType shouldBe QuestionType.OPEN_CHOICE
        ex.options shouldBe listOf("X", "Y", "Z")
    }

    // -------------------------------------------------------------------------
    // userId propagation from ToolContext
    // -------------------------------------------------------------------------

    "execute throws AwaitAnswer carrying the userId from ToolContext" {
        // ToolContext.userId must flow into AwaitAnswer.userId so that
        // AgentInterruptHandler can stamp the QuestionEvent with the right user.
        val userId = UUID.randomUUID()
        val context = mockk<ToolContext>(relaxed = true)
        every { context.userId } returns userId

        val tool = QueryUserTool()
        val ex = shouldThrow<AgentInterrupt.AwaitAnswer> {
            tool.execute(QueryUserTool.Input(question = "Which one?"), context)
        }
        ex.userId shouldBe userId
    }

    "execute throws AwaitAnswer with null userId when ToolContext.userId is null" {
        // When the execution context has no resolved user (webhook, system call, etc.),
        // userId stays null — no artificial fallback is applied.
        // mockk<ToolContext>(relaxed = true) already returns null for UUID? properties,
        // but we make the intent explicit here.
        val context = mockk<ToolContext>(relaxed = true)
        every { context.userId } returns null

        val tool = QueryUserTool()
        val ex = shouldThrow<AgentInterrupt.AwaitAnswer> {
            tool.execute(QueryUserTool.Input(question = "Which one?"), context)
        }
        ex.userId shouldBe null
    }

    // -------------------------------------------------------------------------
    // Tool name convention
    // -------------------------------------------------------------------------

    "name is bare 'queryUser' when configName is null" {
        QueryUserTool(configName = null).name shouldBe "queryUser"
    }

    "name is prefixed when configName is provided" {
        QueryUserTool(configName = "QUERY_USER").name shouldBe "QUERY_USER__queryUser"
    }
})
