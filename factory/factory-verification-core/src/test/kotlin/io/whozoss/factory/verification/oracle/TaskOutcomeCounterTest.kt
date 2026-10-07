package io.whozoss.factory.verification.oracle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure tests for the Gradle/Nx task-outcome counter. */
class TaskOutcomeCounterTest {

    private fun count(output: String) = TaskOutcomeCounter.countTaskOutcomes(output)

    @Test
    fun `counts Gradle task markers`() {
        val output = listOf(
            "> Task :a:compileJava",
            "> Task :b:test UP-TO-DATE",
            "> Task :c:jar FROM-CACHE",
            "> Task :d:foo SKIPPED",
            "> Task :e:bar NO-SOURCE",
        ).joinToString("\n")

        val counts = count(output)
        assertEquals(1, counts.executed)
        assertEquals(1, counts.upToDate)
        assertEquals(1, counts.fromCache)
        assertEquals(2, counts.skipped)
        assertFalse(counts.summaryFound)
        assertEquals(OracleSummaryAbsenceReason.NO_NX_TASKS, counts.summaryAbsenceReason)
        assertFalse(counts.countMismatch)
    }

    @Test
    fun `counts Nx task lines and summary lines without mismatch`() {
        val output = listOf(
            "> nx run proj1:build",
            "> nx run proj2:build  [existing outputs match the cache, left as is]",
            "Nx read the output from the cache instead of running the command for 1 out of 2 tasks.",
            "NX   Successfully ran target build for 2 projects",
        ).joinToString("\n")

        val counts = count(output)
        assertEquals(1, counts.executed)
        assertEquals(1, counts.fromCache)
        assertTrue(counts.summaryFound)
        assertEquals(1, counts.summaryFromCache)
        assertEquals(2, counts.summaryTotal)
        assertNull(counts.summaryAbsenceReason)
        assertFalse(counts.countMismatch)
    }

    @Test
    fun `flags a summary mismatch as a format-change signal`() {
        val output = listOf(
            "> nx run p:build",
            "Nx read the output from the cache instead of running the command for 5 out of 9 tasks.",
        ).joinToString("\n")

        val counts = count(output)
        assertTrue(counts.summaryFound)
        assertTrue(counts.countMismatch)
    }

    @Test
    fun `reports fresh-run when Nx tasks have no summary`() {
        val counts = count("> nx run p:build")
        assertFalse(counts.summaryFound)
        assertEquals(OracleSummaryAbsenceReason.FRESH_RUN, counts.summaryAbsenceReason)
    }

    @Test
    fun `strips ANSI colour sequences before analysis`() {
        val counts = count("\u001b[32m> Task :x:y\u001b[0m")
        assertEquals(1, counts.executed)
        assertEquals(0, counts.upToDate)
    }
}
