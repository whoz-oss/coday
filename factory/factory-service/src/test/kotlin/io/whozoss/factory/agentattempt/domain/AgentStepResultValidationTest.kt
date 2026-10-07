package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of the structured business result schema, the canonical
 * hashing helpers and the safe-identifier grammar.
 *
 * No Spring context is started.
 */
class AgentStepResultValidationTest {

    private val mapper = ObjectMapper()

    private fun json(value: String): JsonNode = mapper.readTree(value)

    private fun valid(): JsonNode = json(
        """
        {
          "status": "PASS",
          "summary": "implemented the feature",
          "claims": { "modifiedFiles": ["libs/a.ts", "libs/b.ts"] },
          "artifacts": [
            { "kind": "report", "encoding": "markdown", "content": "# done" }
          ],
          "findings": [
            { "severity": "warning", "code": "W1", "summary": "check this", "file": "libs/a.ts", "line": 12 }
          ]
        }
        """.trimIndent(),
    )

    private fun withSummary(summary: String): JsonNode =
        json("""{"status":"PASS","summary":$summary,"claims":{"modifiedFiles":[]}}""")

    private fun withFindings(findings: String): JsonNode =
        json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"findings":[$findings]}""")

    @Test
    fun `accepts a fully populated business result`() {
        assertThat(AgentStepResultValidation.validateBusiness(valid())).isTrue()
    }

    @Test
    fun `accepts a minimal business result`() {
        val node = json("""{"status":"FAIL","summary":"nope","claims":{"modifiedFiles":[]}}""")
        assertThat(AgentStepResultValidation.validateBusiness(node)).isTrue()
    }

    @Test
    fun `rejects a null or non-object payload`() {
        assertThat(AgentStepResultValidation.validateBusiness(null)).isFalse()
        assertThat(AgentStepResultValidation.validateBusiness(json("[]"))).isFalse()
        assertThat(AgentStepResultValidation.validateBusiness(json("\"text\""))).isFalse()
    }

    @Test
    fun `rejects unknown top level keys`() {
        val node = json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"extra":true}""")
        assertThat(AgentStepResultValidation.validateBusiness(node)).isFalse()
    }

    @Test
    fun `rejects an invalid status`() {
        val node = json("""{"status":"MAYBE","summary":"ok","claims":{"modifiedFiles":[]}}""")
        assertThat(AgentStepResultValidation.validateBusiness(node)).isFalse()
    }

    @Test
    fun `rejects an empty or oversized summary`() {
        assertThat(AgentStepResultValidation.validateBusiness(withSummary("\"\""))).isFalse()
        val tooLong = mapper.writeValueAsString("x".repeat(AgentStepResultLimits.SUMMARY + 1))
        assertThat(AgentStepResultValidation.validateBusiness(withSummary(tooLong))).isFalse()
        val atLimit = mapper.writeValueAsString("x".repeat(AgentStepResultLimits.SUMMARY))
        assertThat(AgentStepResultValidation.validateBusiness(withSummary(atLimit))).isTrue()
    }

    @Test
    fun `rejects malformed claims`() {
        assertThat(AgentStepResultValidation.validateBusiness(json("""{"status":"PASS","summary":"ok"}"""))).isFalse()
        assertThat(
            AgentStepResultValidation.validateBusiness(
                json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[],"other":[]}}"""),
            ),
        ).isFalse()
        assertThat(
            AgentStepResultValidation.validateBusiness(
                json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[""]}}"""),
            ),
        ).isFalse()
        val tooLongFile = mapper.writeValueAsString("x".repeat(AgentStepResultLimits.MODIFIED_FILE_LENGTH + 1))
        assertThat(
            AgentStepResultValidation.validateBusiness(
                json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[$tooLongFile]}}"""),
            ),
        ).isFalse()
    }

    @Test
    fun `rejects artifacts with the wrong encoding, empty content or unknown keys`() {
        val wrongEncoding = json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},
                "artifacts":[{"kind":"k","encoding":"json","content":"x"}]}""",
        )
        assertThat(AgentStepResultValidation.validateBusiness(wrongEncoding)).isFalse()

        val emptyContent = json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},
                "artifacts":[{"kind":"k","encoding":"markdown","content":""}]}""",
        )
        assertThat(AgentStepResultValidation.validateBusiness(emptyContent)).isFalse()

        val unknownKey = json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},
                "artifacts":[{"kind":"k","encoding":"markdown","content":"x","size":1}]}""",
        )
        assertThat(AgentStepResultValidation.validateBusiness(unknownKey)).isFalse()
    }

    @Test
    fun `rejects oversized artifact content and too many artifacts`() {
        val oversized = json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},
                "artifacts":[{"kind":"k","encoding":"markdown","content":"${"a".repeat(262145)}"}]}""",
        )
        assertThat(AgentStepResultValidation.validateBusiness(oversized)).isFalse()

        val atLimit = json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},
                "artifacts":[{"kind":"k","encoding":"markdown","content":"${"a".repeat(262144)}"}]}""",
        )
        assertThat(AgentStepResultValidation.validateBusiness(atLimit)).isTrue()

        val tooMany = json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},
                "artifacts":[${(1..9).joinToString(",") { """{"kind":"k","encoding":"markdown","content":"x"}""" }}]}""",
        )
        assertThat(AgentStepResultValidation.validateBusiness(tooMany)).isFalse()
    }

    @Test
    fun `rejects malformed findings`() {
        assertThat(AgentStepResultValidation.validateBusiness(withFindings(
            """{"severity":"critical","code":"C","summary":"s"}""",
        ))).isFalse()
        assertThat(AgentStepResultValidation.validateBusiness(withFindings(
            """{"severity":"info","code":"C","summary":"s","line":0}""",
        ))).isFalse()
        assertThat(AgentStepResultValidation.validateBusiness(withFindings(
            """{"severity":"info","code":"C","summary":"s","extra":1}""",
        ))).isFalse()
        assertThat(AgentStepResultValidation.validateBusiness(withFindings(
            """{"severity":"info","code":"C","summary":"s","file":""}""",
        ))).isFalse()
    }

    @Test
    fun `accepts every allowed severity`() {
        listOf("info", "warning", "error", "blocking").forEach { severity ->
            assertThat(AgentStepResultValidation.validateBusiness(withFindings(
                """{"severity":"$severity","code":"C","summary":"s"}""",
            )))
                .withFailMessage("severity %s should be accepted", severity)
                .isTrue()
        }
    }

    @Test
    fun `parses a validated business result into typed models`() {
        val parsed = AgentStepResultValidation.parseBusiness(valid())

        assertThat(parsed.status).isEqualTo(AgentStepResultStatus.PASS)
        assertThat(parsed.summary).isEqualTo("implemented the feature")
        assertThat(parsed.claims.modifiedFiles).containsExactly("libs/a.ts", "libs/b.ts")
        assertThat(parsed.artifacts).hasSize(1)
        assertThat(parsed.artifacts.single().encoding).isEqualTo("markdown")
        assertThat(parsed.findings).hasSize(1)
        assertThat(parsed.findings.single().line).isEqualTo(12)
    }
}
