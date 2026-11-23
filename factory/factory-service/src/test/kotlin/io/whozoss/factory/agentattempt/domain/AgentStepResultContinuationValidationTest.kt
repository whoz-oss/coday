package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Structural check of the optional `result.continuation` field (the deep checks live in the expansion service). */
class AgentStepResultContinuationValidationTest {

    private val mapper = ObjectMapper()

    private fun result(status: String, continuation: String?): JsonNode = mapper.readTree(
        """{"status":"$status","summary":"ok","claims":{"modifiedFiles":[]}""" +
            (continuation?.let { ""","continuation":$it""" } ?: "") + "}",
    )

    private val oneStep = """{"steps":[{"id":"a"}]}"""

    @Test
    fun `a continuation is accepted on PASS`() {
        assertThat(AgentStepResultValidation.violations(result("PASS", oneStep))).isEmpty()
    }

    @Test
    fun `a continuation is rejected on FAIL and NEEDS_RESEARCH`() {
        listOf("FAIL", "NEEDS_RESEARCH").forEach { status ->
            val violations = AgentStepResultValidation.violations(result(status, oneStep))
            assertThat(violations).extracting("path", "code")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("result.continuation", "INVALID_CONTINUATION"))
        }
    }

    @Test
    fun `badly shaped continuations are rejected`() {
        listOf(
            """"text"""",
            "[]",
            """{"steps":"nope"}""",
            """{"steps":[]}""",
            """{"steps":["a"]}""",
            """{"steps":[{"id":"a"}],"extra":true}""",
        ).forEach { continuation ->
            val violations = AgentStepResultValidation.violations(result("PASS", continuation))
            assertThat(violations).describedAs(continuation).isNotEmpty()
            assertThat(violations.map { it.code }).describedAs(continuation).containsOnly("INVALID_CONTINUATION")
        }
    }

    @Test
    fun `more than fifty steps are rejected with MAX_ITEMS`() {
        val steps = (1..51).joinToString(",") { """{"id":"s$it"}""" }

        val violations = AgentStepResultValidation.violations(result("PASS", """{"steps":[$steps]}"""))

        assertThat(violations.map { it.code }).containsExactly("MAX_ITEMS")
    }

    @Test
    fun `the boolean validation stays equivalent to the absence of violations`() {
        listOf(result("PASS", oneStep), result("FAIL", oneStep), result("PASS", "[]"), result("PASS", null)).forEach {
            assertThat(AgentStepResultValidation.validateBusiness(it))
                .isEqualTo(AgentStepResultValidation.violations(it).isEmpty())
        }
    }

    @Test
    fun `the continuation is parsed as a raw map and absent when not sent`() {
        val parsed = AgentStepResultValidation.parseBusiness(result("PASS", oneStep))
        assertThat(parsed.continuation).isEqualTo(mapOf("steps" to listOf(mapOf("id" to "a"))))

        assertThat(AgentStepResultValidation.parseBusiness(result("PASS", null)).continuation).isNull()
    }

    @Test
    fun `violations never repeat the submitted values`() {
        val violations = AgentStepResultValidation.violations(result("PASS", """{"steps":[],"secret-field":"secret-value"}"""))

        assertThat(violations.toString()).doesNotContain("secret-value").doesNotContain("secret-field")
    }
}
