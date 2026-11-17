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
    fun `reports safe actionable violations without payload values`() {
        val node = json("""{"status":"PASS","summary":"secret-value","claims":{"modifiedFiles":[""]}}""")

        val violations = AgentStepResultValidation.violations(node)

        assertThat(violations).containsExactly(
            AgentStepResultValidation.Violation(
                "result.claims.modifiedFiles[0]",
                "MIN_LENGTH",
                "must not be empty",
            ),
        )
        assertThat(violations.toString()).doesNotContain("secret-value")
    }

    @Test
    fun `reports required nested claims fields`() {
        assertThat(AgentStepResultValidation.violations(json("""{"status":"PASS","summary":"ok","claims":{}}""")))
            .containsExactly(
                AgentStepResultValidation.Violation(
                    "result.claims.modifiedFiles",
                    "REQUIRED",
                    "is required",
                ),
            )
    }

    @Test
    fun `reports nested artifact finding and delivery diagnostics`() {
        val node = json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[],"deliveryAnalysis":{"changeType":"other","ticket":"private","title":""}},"artifacts":[{"kind":"","encoding":"json","content":""}],"findings":[{"severity":"critical","code":"","summary":"","line":0}]}""",
        )

        assertThat(AgentStepResultValidation.violations(node).map { it.path }).contains(
            "result.claims.deliveryAnalysis.changeType",
            "result.claims.deliveryAnalysis.ticket",
            "result.claims.deliveryAnalysis.title",
            "result.artifacts[0].kind",
            "result.artifacts[0].encoding",
            "result.artifacts[0].content",
            "result.findings[0].severity",
            "result.findings[0].code",
            "result.findings[0].summary",
            "result.findings[0].line",
        )
    }

    @Test
    fun `unknown field diagnostics do not echo attacker controlled names`() {
        val unknown = "sensitive-" + "x".repeat(5000)
        val node = json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"$unknown":true}""")

        val violations = AgentStepResultValidation.violations(node)

        assertThat(violations).containsExactly(
            AgentStepResultValidation.Violation("result", "UNKNOWN_FIELD", "contains one or more fields that are not allowed"),
        )
        assertThat(violations.toString()).doesNotContain(unknown)
    }

    @Test
    fun `boolean validation is exactly equivalent to absence of violations`() {
        val samples = listOf(
            valid(),
            json("null"),
            json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}"""),
            json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"artifacts":[{"kind":"x","encoding":"json","content":"x"}]}"""),
            json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"findings":[{"severity":"info","code":"x","summary":"x","line":0}]}"""),
        )
        samples.forEach { sample ->
            assertThat(AgentStepResultValidation.validateBusiness(sample))
                .isEqualTo(AgentStepResultValidation.violations(sample).isEmpty())
        }
    }

    @Test
    fun `rejects an invalid status`() {
        val node = json("""{"status":"MAYBE","summary":"ok","claims":{"modifiedFiles":[]}}""")
        assertThat(AgentStepResultValidation.validateBusiness(node)).isFalse()
    }

    @Test
    fun `accepts the three verdicts including NEEDS_RESEARCH`() {
        listOf("PASS", "FAIL", "NEEDS_RESEARCH").forEach { status ->
            val node = json("""{"status":"$status","summary":"ok","claims":{"modifiedFiles":[]}}""")
            assertThat(AgentStepResultValidation.validateBusiness(node))
                .withFailMessage("status %s should be accepted", status)
                .isTrue()
        }
    }

    @Test
    fun `parses NEEDS_RESEARCH preserving findings and artifacts`() {
        val node = json(
            """
            {
              "status": "NEEDS_RESEARCH",
              "summary": "missing the upstream contract",
              "claims": { "modifiedFiles": ["libs/a.ts"] },
              "artifacts": [ { "kind": "report", "encoding": "markdown", "content": "# blocked" } ],
              "findings": [ { "severity": "blocking", "code": "MISSING", "summary": "need research" } ]
            }
            """.trimIndent(),
        )
        val parsed = AgentStepResultValidation.parseBusiness(node)
        assertThat(parsed.status).isEqualTo(AgentStepResultStatus.NEEDS_RESEARCH)
        assertThat(parsed.artifacts).hasSize(1)
        assertThat(parsed.findings.single().code).isEqualTo("MISSING")
    }

    @Test
    fun `accepts and parses a non-negative expected amendment sequence`() {
        val snake = json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"expected_amendment_seq":3}""")
        assertThat(AgentStepResultValidation.validateBusiness(snake)).isTrue()
        assertThat(AgentStepResultValidation.parseBusiness(snake).expectedAmendmentSeq).isEqualTo(3L)

        val camel = json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"expectedAmendmentSeq":0}""")
        assertThat(AgentStepResultValidation.validateBusiness(camel)).isTrue()
        assertThat(AgentStepResultValidation.parseBusiness(camel).expectedAmendmentSeq).isEqualTo(0L)
    }

    @Test
    fun `rejects a negative or non-integer expected amendment sequence`() {
        val negative = json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"expected_amendment_seq":-1}""")
        assertThat(AgentStepResultValidation.validateBusiness(negative)).isFalse()

        val fractional = json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"expected_amendment_seq":1.5}""")
        assertThat(AgentStepResultValidation.validateBusiness(fractional)).isFalse()
    }

    @Test
    fun `rejects divergent snake and camel amendment sequences`() {
        val divergent = json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]},"expected_amendment_seq":1,"expectedAmendmentSeq":2}""",
        )
        assertThat(AgentStepResultValidation.validateBusiness(divergent)).isFalse()
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
    fun `accepts and parses user shaped delivery analysis with run brief and empty modified files`() {
        val node = json(
            """{"status":"PASS","summary":"done","claims":{"modifiedFiles":[],"deliveryAnalysis":{"baseBranch":"sprint","changeType":"bugfix","ticket":"WZ-33157","title":"Complete RAWeb 5.4 accessible table names"}},"artifacts":[{"kind":"run-brief","encoding":"markdown","content":"# Run brief"}],"findings":[{"severity":"blocking","code":"A11Y","summary":"Needs verification"}]}""",
        )

        assertThat(AgentStepResultValidation.validateBusiness(node)).isTrue()
        val parsed = AgentStepResultValidation.parseBusiness(node)
        assertThat(parsed.claims.modifiedFiles).isEmpty()
        assertThat(parsed.claims.deliveryAnalysis?.baseBranch).isEqualTo("sprint")
        assertThat(parsed.claims.deliveryAnalysis?.changeType).isEqualTo("bugfix")
        assertThat(parsed.claims.deliveryAnalysis?.ticket).isEqualTo("WZ-33157")
        assertThat(parsed.claims.deliveryAnalysis?.title).isEqualTo("Complete RAWeb 5.4 accessible table names")
    }

    @Test
    fun `accepts GitHub and generic Jira ticket identifiers`() {
        listOf("#1234", "WZ-123", "ABC-123").forEach { ticket ->
            val node = json(
                """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[],"deliveryAnalysis":{"changeType":"bugfix","ticket":"$ticket","title":"title"}}}""",
            )
            assertThat(AgentStepResultValidation.validateBusiness(node)).withFailMessage(ticket).isTrue()
            assertThat(AgentStepResultValidation.parseBusiness(node).claims.deliveryAnalysis?.ticket).isEqualTo(ticket)
        }
    }

    @Test
    fun `rejects invalid delivery analysis fields types bounds and unknown keys`() {
        val invalid = listOf(
            """{"changeType":"chore","ticket":"WZ-33157","title":"title"}""",
            """{"changeType":"bugfix","ticket":"#0","title":"title"}""",
            """{"changeType":"bugfix","ticket":"ABC-0","title":"title"}""",
            """{"changeType":"bugfix","ticket":"abc-123","title":"title"}""",
            """{"changeType":"bugfix","ticket":"1234","title":"title"}""",
            """{"changeType":"bugfix","ticket":"#123x","title":"title"}""",
            """{"changeType":"bugfix","ticket":33157,"title":"title"}""",
            """{"changeType":"bugfix","ticket":"WZ-33157","title":""}""",
            """{"changeType":"bugfix","ticket":"WZ-33157","title":"${"x".repeat(201)}"}""",
            """{"changeType":"bugfix","ticket":"WZ-33157","title":"title","baseBranch":""}""",
            """{"changeType":"bugfix","ticket":"WZ-33157","title":"title","baseBranch":"${"x".repeat(256)}"}""",
            """{"changeType":"bugfix","ticket":"WZ-33157","title":"title","extra":true}""",
        )
        invalid.forEach { delivery ->
            val node = json("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[],"deliveryAnalysis":$delivery}}""")
            assertThat(AgentStepResultValidation.validateBusiness(node)).withFailMessage(delivery).isFalse()
        }
    }

    @Test
    fun `rejects null delivery analysis while absence remains valid`() {
        assertThat(AgentStepResultValidation.validateBusiness(json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[],"deliveryAnalysis":null}}""",
        ))).isFalse()
        assertThat(AgentStepResultValidation.validateBusiness(json(
            """{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}""",
        ))).isTrue()
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
