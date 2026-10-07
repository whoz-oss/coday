package io.whozoss.factory.verification.plan

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure plan parsing / plan-gate / claims-gate tests. */
class PlanParsingTest {

    // ------------------------------------------------------------- extract

    @Test
    fun `extracts a fenced json block first`() {
        val text = "blah\n```json\n{\"a\":1}\n```\nrest"
        assertEquals("{\"a\":1}", PlanParsing.extractJsonFragment(text))
    }

    @Test
    fun `extracts a plain fenced block`() {
        val text = "blah\n```\n{\"b\":2}\n```"
        assertEquals("{\"b\":2}", PlanParsing.extractJsonFragment(text))
    }

    @Test
    fun `falls back to the first balanced brace`() {
        val text = "prefix {\"c\":{\"d\":3}} suffix"
        assertEquals("{\"c\":{\"d\":3}}", PlanParsing.extractJsonFragment(text))
    }

    @Test
    fun `returns null when there is no json`() {
        assertNull(PlanParsing.extractJsonFragment("nothing here"))
    }

    // ------------------------------------------------------------ isSafePath

    @Test
    fun `isSafePath accepts relative paths`() {
        assertTrue(PlanParsing.isSafePath("libs/a/src/x.ts"))
    }

    @Test
    fun `isSafePath rejects absolute and parent paths`() {
        assertFalse(PlanParsing.isSafePath("/etc/passwd"))
        assertFalse(PlanParsing.isSafePath("../outside"))
        assertFalse(PlanParsing.isSafePath("a/../b"))
        assertFalse(PlanParsing.isSafePath(null))
    }

    // ------------------------------------------------------------- parsePlan

    @Test
    fun `parsePlan accepts a valid plan`() {
        val message = """
            Plan:
            ```json
            {"files":["libs/a/x.ts"],"doneWhen":"tests verts","steps":["un","deux"]}
            ```
        """.trimIndent()

        val result = PlanParsing.parsePlan(message)
        val plan = assertIs<PlanParsing.ParseResult.Ok>(result).plan
        assertEquals(listOf("libs/a/x.ts"), plan.files)
        assertEquals("tests verts", plan.doneWhen)
        assertEquals(listOf("un", "deux"), plan.steps)
    }

    @Test
    fun `parsePlan rejects a missing json block`() {
        val result = PlanParsing.parsePlan("pas de json ici")
        assertTrue(result is PlanParsing.ParseResult.Failure)
    }

    @Test
    fun `parsePlan rejects invalid json`() {
        val result = PlanParsing.parsePlan("```json\n{not json}\n```")
        val failure = assertIs<PlanParsing.ParseResult.Failure>(result)
        assertTrue(failure.error.startsWith("JSON invalide"))
    }

    @Test
    fun `parsePlan rejects empty files`() {
        val result = PlanParsing.parsePlan("```json\n{\"files\":[],\"doneWhen\":\"x\"}\n```")
        assertTrue(result is PlanParsing.ParseResult.Failure)
    }

    @Test
    fun `parsePlan rejects unsafe paths`() {
        val result = PlanParsing.parsePlan("```json\n{\"files\":[\"../x\"],\"doneWhen\":\"x\"}\n```")
        val failure = assertIs<PlanParsing.ParseResult.Failure>(result)
        assertTrue(failure.error.contains("Chemin invalide"))
    }

    @Test
    fun `parsePlan rejects a missing doneWhen`() {
        val result = PlanParsing.parsePlan("```json\n{\"files\":[\"a.ts\"]}\n```")
        assertTrue(result is PlanParsing.ParseResult.Failure)
    }

    // --------------------------------------------------------- checkPlanFiles

    @Test
    fun `checkPlanFiles reports missing files`() {
        val root = Files.createTempDirectory("plan-gate")
        Files.createDirectories(root.resolve("sub"))
        Files.writeString(root.resolve("a.txt"), "a")
        Files.writeString(root.resolve("sub/b.txt"), "b")

        val check = PlanParsing.checkPlanFiles(listOf("a.txt", "sub/b.txt", "missing.txt"), root)
        assertEquals(3, check.fileCount)
        assertEquals(listOf("missing.txt"), check.missingFiles)
        assertEquals(listOf("a.txt", "sub/b.txt", "missing.txt"), check.plannedFiles)
    }

    // ---------------------------------------------------------- compareClaims

    @Test
    fun `compareClaims matches when plan equals reality`() {
        val comparison = PlanParsing.compareClaims(
            plannedFiles = listOf("a", "b"),
            actualModified = listOf("a", "b"),
            actualUntracked = emptyList(),
        )
        assertTrue(comparison.claimsMatch)
        assertTrue(comparison.unplannedFiles.isEmpty())
        assertTrue(comparison.untouchedPlannedFiles.isEmpty())
    }

    @Test
    fun `compareClaims reports unplanned files`() {
        val comparison = PlanParsing.compareClaims(
            plannedFiles = listOf("a"),
            actualModified = listOf("a", "c"),
            actualUntracked = listOf("d"),
        )
        assertFalse(comparison.claimsMatch)
        assertEquals(listOf("c", "d"), comparison.unplannedFiles)
        assertEquals(emptyList(), comparison.untouchedPlannedFiles)
    }

    @Test
    fun `compareClaims reports untouched planned files`() {
        val comparison = PlanParsing.compareClaims(
            plannedFiles = listOf("a", "b"),
            actualModified = listOf("a"),
            actualUntracked = emptyList(),
        )
        assertFalse(comparison.claimsMatch)
        assertEquals(listOf("b"), comparison.untouchedPlannedFiles)
    }
}
