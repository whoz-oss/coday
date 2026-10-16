package io.whozoss.factory.forge.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of the Forge domain (no Spring, no database).
 *
 * These run on every machine, unlike the Testcontainers-backed integration
 * tests, and pin the JSONL parsing/projection, spec validation and hashing.
 */
class ForgeDomainUnitTest {

    @Test
    fun `ledger parses, rejects invalid schema and projects the display state`() {
        val raw = listOf(
            """{"schemaVersion":1,"event":"run_started","runId":"epic_1","runType":"EpicRun","workflow":"forge-epic-v1","workItem":{"id":"EPIC-1","kind":"Epic"},"roots":{"repoRoot":"/r"},"at":"2026-01-01T00:00:00.000Z"}""",
            """{"schemaVersion":1,"event":"story_run_created","runId":"story_1","parentRunId":"epic_1","runType":"StoryRun","ordinal":1,"workItem":{"id":"STORY-1","kind":"Story"}}""",
            """{"schemaVersion":1,"event":"gate_started","runId":"epic_1","gate":"G1","attempt":1,"status":"waiting_human","policyVersion":"forge-g1-human-v1"}""",
            """{"schemaVersion":1,"event":"human_decision_recorded","runId":"epic_1","gate":"G1","attempt":1,"decision":{"outcome":"approved"}}""",
        ).joinToString("\n")

        val events = parseForgeLedgerLines(raw)
        assertThat(events).hasSize(4)
        val projection = projectForgeRun(events)!!
        assertThat(projection["runId"]).isEqualTo("epic_1")
        assertThat(projection["status"]).isEqualTo("approved")
        assertThat(projection["stories"] as List<*>).hasSize(1)

        assertThatThrownBy { parseForgeLedgerLines("{bad}") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { parseForgeLedgerLines("""{"schemaVersion":2}""") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `G1 evidence hash is deterministic and key-order insensitive`() {
        val events = listOf(
            mapOf("event" to "run_started", "runId" to "r1", "schemaVersion" to 1),
            mapOf("event" to "gate_started", "runId" to "r1", "gate" to "G1", "attempt" to 1),
        )
        val first = computeG1EvidenceSetHash(events, "r1", 1)
        val second = computeG1EvidenceSetHash(events, "r1", 1)
        assertThat(first).isEqualTo(second).startsWith("sha256:")
        assertThat(ForgeHumanDecision.canonicalG1(mapOf("b" to 1, "a" to 2)))
            .isEqualTo("""{"a":2,"b":1}""")
    }

    @Test
    fun `epic spec frontmatter parses and validates`() {
        val content = """
            ---
            schemaVersion: 1
            workItem:
              id: EPIC-1
              kind: Epic
            scope:
              allow:
                - src/*
              create:
                - src/new.ts
              deny:
                - src/secret.ts
            oracles:
              - front.build
            ---
        """.trimIndent() + "\n"
        val match = ForgeSpec.FORGE_SPEC_FRONTMATTER_PATTERN.find(content)!!
        val frontmatter = ForgeSpec.parseForgeSpecFrontmatter(match.groupValues[1])
        ForgeSpec.validateForgeSpecSchema(frontmatter, ForgeWorkItem("EPIC-1", "Epic"))
        assertThat(frontmatter["schemaVersion"]).isEqualTo(1)
        assertThat((frontmatter["scope"] as Map<*, *>)["allow"]).isEqualTo(listOf("src/*"))

        assertThatThrownBy {
            ForgeSpec.validateForgeSpecSchema(frontmatter, ForgeWorkItem("OTHER", "Epic"))
        }.isInstanceOf(ForgeCodedException::class.java)
    }

    @Test
    fun `story spec inheritance detects violations`() {
        val epic = mapOf(
            "scope" to mapOf(
                "allow" to listOf("src/a.ts"),
                "create" to listOf("src/new.ts"),
                "deny" to listOf("src/secret.ts"),
            ),
            "oracles" to listOf("front.build"),
        )
        val valid = mapOf(
            "scope" to mapOf(
                "allow" to listOf("src/a.ts"),
                "create" to listOf("src/new.ts"),
                "deny" to listOf("src/secret.ts"),
            ),
            "oracles" to listOf("front.build"),
        )
        assertThat(ForgeStorySpec.validateInheritance(valid, epic).valid).isTrue()

        val violation = mapOf(
            "scope" to mapOf(
                "allow" to listOf("src/other.ts"),
                "create" to listOf("src/nope.ts"),
                "deny" to emptyList<String>(),
            ),
            "oracles" to listOf("back.build"),
        )
        val result = ForgeStorySpec.validateInheritance(violation, epic)
        assertThat(result.valid).isFalse()
        assertThat(result.violations.map { it.code }).contains(
            "G2_US_ALLOW_EXCEEDS_EPIC",
            "G2_US_CREATE_EXCEEDS_EPIC",
            "G2_US_DENY_WEAKER_THAN_EPIC",
            "G2_US_ORACLE_UNKNOWN_IN_EPIC",
        )
    }

    @Test
    fun `plan parsing rejects absolute and escaping paths`() {
        assertThat(ForgePlanParser.isSafePath("/etc/passwd")).isFalse()
        assertThat(ForgePlanParser.isSafePath("../escape.ts")).isFalse()
        assertThat(ForgePlanParser.isSafePath("src/a.ts")).isTrue()

        val parsed = ForgePlanParser.parsePlan("```json\n{\"files\":[\"src/a.ts\"],\"doneWhen\":\"done\"}\n```")
        assertThat(parsed.isSuccess).isTrue()
        assertThat(parsed.getOrNull()!!.files).containsExactly("src/a.ts")
    }

    @Test
    fun `the jira helpers extract ids, flatten adf and budget comments`() {
        assertThat(JiraDomain.extractTicketId("https://foo.atlassian.net/browse/proj-1234")).isEqualTo("PROJ-1234")
        assertThat(JiraDomain.extractTicketId("proj-1234")).isEqualTo("PROJ-1234")
        assertThat(JiraDomain.extractTicketId("pas-un-ticket")).isNull()
        val adf = mapOf(
            "type" to "doc",
            "content" to listOf(
                mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to "Bonjour"))),
            ),
        )
        assertThat(JiraDomain.extractAdfText(adf).trim()).isEqualTo("Bonjour")
        val comments = (1..3).map { JiraComment("author", "2026-01-01", "body-$it") }
        val (included, omitted) = JiraDomain.applyCommentBudget(comments, 72)
        assertThat(included).hasSize(1)
        assertThat(omitted).isEqualTo(2)
    }

    @Test
    fun `the workflow adapter validates gate order and outcomes`() {
        val gates = (1..4).associate { index ->
            "gate_$index" to mapOf(
                "startedAt" to "2026-01-0${index}T00:00:00Z",
                "decidedAt" to "2026-01-0${index + 1}T00:00:00Z",
                "humanDecision" to "approved",
            )
        }
        val adapted = adaptForgeRunToWorkflowProjection(
            mapOf(
                "ticketId" to "ABC-1",
                "ticketSummary" to "Fix",
                "gates" to gates,
                "runOutcome" to mapOf("status" to "completed"),
            ),
        )
        assertThat(adapted["ok"]).isEqualTo(true)
        assertThat((adapted["projection"] as Map<*, *>)["status"]).isEqualTo("completed")

        val invalid = adaptForgeRunToWorkflowProjection(mapOf("ticketId" to "nope"))
        assertThat((invalid["error"] as Map<*, *>)["code"]).isEqualTo("INVALID_FORGE_RUN")

        val impossible = adaptForgeRunToWorkflowProjection(
            mapOf(
                "ticketId" to "ABC-2",
                "gates" to mapOf("gate_2" to mapOf("startedAt" to "2026-01-01T00:00:00Z")),
                "runOutcome" to mapOf("status" to "in-progress"),
            ),
        )
        assertThat((impossible["error"] as Map<*, *>)["code"]).isEqualTo("IMPOSSIBLE_FORGE_GATE_ORDER")
    }

    @Test
    fun `front build host map is validated and normalized`() {
        val map = ForgeFrontOracleResolution.parseFrontBuildHostMap("""{"app":["host-b","host-a","host-a"]}""")
        assertThat(map["app"]).containsExactly("host-a", "host-b")
        assertThatThrownBy { ForgeFrontOracleResolution.parseFrontBuildHostMap("not json") }
            .isInstanceOf(ForgeCodedException::class.java)
    }
}
