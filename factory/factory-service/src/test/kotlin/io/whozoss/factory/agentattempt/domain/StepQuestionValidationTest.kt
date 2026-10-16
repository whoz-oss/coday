package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit test of the step-question schema validation (Phase 4
 * ask-step-question), mirroring [AgentStepResultValidationTest].
 */
class StepQuestionValidationTest {

    private val mapper = jacksonObjectMapper()

    private fun node(json: String) = mapper.readTree(json)

    @Test
    fun `a minimal free-text question is valid and parses`() {
        val value = node("""{"prompt":"Proceed?","type":"FREE_TEXT","contextHash":"sha256:abc"}""")

        assertThat(StepQuestionValidation.validate(value)).isTrue()
        val parsed = StepQuestionValidation.parse(value)
        assertThat(parsed.prompt).isEqualTo("Proceed?")
        assertThat(parsed.type).isEqualTo(StepQuestionType.FREE_TEXT)
        assertThat(parsed.options).isEmpty()
        assertThat(parsed.recipientRole).isNull()
        assertThat(parsed.contextHash).isEqualTo("sha256:abc")
        assertThat(parsed.expiresAt).isNull()
    }

    @Test
    fun `a full single-choice question with options, recipient and expiry is valid`() {
        val value = node(
            """{"prompt":"Pick one","type":"SINGLE_CHOICE","options":["A","B"],"recipientRole":"reviewer",
                "contextHash":"sha256:def","expiresAt":"2999-01-01T00:00:00Z"}""",
        )

        assertThat(StepQuestionValidation.validate(value)).isTrue()
        val parsed = StepQuestionValidation.parse(value)
        assertThat(parsed.type).isEqualTo(StepQuestionType.SINGLE_CHOICE)
        assertThat(parsed.options).containsExactly("A", "B")
        assertThat(parsed.recipientRole).isEqualTo("reviewer")
        assertThat(parsed.expiresAt).isNotNull()
    }

    @Test
    fun `missing prompt, missing type or missing contextHash are rejected`() {
        assertThat(StepQuestionValidation.validate(node("""{"type":"FREE_TEXT","contextHash":"h"}"""))).isFalse()
        assertThat(StepQuestionValidation.validate(node("""{"prompt":"P","contextHash":"h"}"""))).isFalse()
        assertThat(StepQuestionValidation.validate(node("""{"prompt":"P","type":"FREE_TEXT"}"""))).isFalse()
        assertThat(StepQuestionValidation.validate(null)).isFalse()
        assertThat(StepQuestionValidation.validate(node(""""not-an-object""""))).isFalse()
    }

    @Test
    fun `prompt bounds are enforced`() {
        val tooLong = "x".repeat(StepQuestionLimits.PROMPT + 1)
        assertThat(StepQuestionValidation.validate(node("""{"prompt":"","type":"FREE_TEXT","contextHash":"h"}"""))).isFalse()
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"$tooLong","type":"FREE_TEXT","contextHash":"h"}""")),
        ).isFalse()
        val atBound = "x".repeat(StepQuestionLimits.PROMPT)
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"$atBound","type":"FREE_TEXT","contextHash":"h"}""")),
        ).isTrue()
    }

    @Test
    fun `an unknown question type is rejected`() {
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"MULTIPLE_CHOICE","contextHash":"h"}""")),
        ).isFalse()
    }

    @Test
    fun `single choice requires at least one option while free text forbids none`() {
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"SINGLE_CHOICE","contextHash":"h"}""")),
        ).isFalse()
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"SINGLE_CHOICE","options":[],"contextHash":"h"}""")),
        ).isFalse()
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"OPEN_CHOICE","options":["A"],"contextHash":"h"}""")),
        ).isTrue()
    }

    @Test
    fun `options count and length bounds are enforced`() {
        val tooMany = (1..StepQuestionLimits.OPTIONS + 1).joinToString(",") { "\"o$it\"" }
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"OPEN_CHOICE","options":[$tooMany],"contextHash":"h"}""")),
        ).isFalse()
        val tooLong = "x".repeat(StepQuestionLimits.OPTION_LENGTH + 1)
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"OPEN_CHOICE","options":["$tooLong"],"contextHash":"h"}""")),
        ).isFalse()
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"OPEN_CHOICE","options":[""],"contextHash":"h"}""")),
        ).isFalse()
    }

    @Test
    fun `recipient role and context hash bounds are enforced`() {
        val longRole = "r".repeat(StepQuestionLimits.RECIPIENT_ROLE + 1)
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"FREE_TEXT","contextHash":"h","recipientRole":"$longRole"}""")),
        ).isFalse()
        val longHash = "h".repeat(StepQuestionLimits.CONTEXT_HASH + 1)
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"FREE_TEXT","contextHash":"$longHash"}""")),
        ).isFalse()
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"FREE_TEXT","contextHash":""}""")),
        ).isFalse()
    }

    @Test
    fun `expiresAt must be an ISO instant`() {
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"FREE_TEXT","contextHash":"h","expiresAt":"tomorrow"}""")),
        ).isFalse()
        assertThat(
            StepQuestionValidation.validate(node("""{"prompt":"P","type":"FREE_TEXT","contextHash":"h","expiresAt":42}""")),
        ).isFalse()
    }

    @Test
    fun `unknown fields are rejected (additionalProperties false semantics)`() {
        assertThat(
            StepQuestionValidation.validate(
                node("""{"prompt":"P","type":"FREE_TEXT","contextHash":"h","attemptId":"spoof"}"""),
            ),
        ).isFalse()
        assertThat(
            StepQuestionValidation.validate(
                node("""{"prompt":"P","type":"FREE_TEXT","contextHash":"h","namespaceId":"spoof"}"""),
            ),
        ).isFalse()
    }
}
