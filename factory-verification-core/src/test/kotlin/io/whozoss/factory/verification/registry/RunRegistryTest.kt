package io.whozoss.factory.verification.registry

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSONL registry tests. Verifies the strict fail-by-default invariant and the
 * byte-compatible line format the Kotlin `LegacyRunService` already reads.
 */
class RunRegistryTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-09-27T11:33:35.123Z"), ZoneOffset.UTC)

    private fun registry(dir: Path, id: String = "run-1"): RunRegistry =
        RunRegistry(dir, fixedClock) { id }

    @Test
    fun `startPhase writes fail immediately`() {
        val dir = Files.createTempDirectory("registry-fail-default")
        val registry = registry(dir)
        val run = registry.createRun("us-loop", "ns-1")

        val phase = registry.startPhase(run, "preflight", PhaseKind.CODE)

        val lines = Files.readAllLines(run.filePath)
        assertEquals(
            "{\"kind\":\"run_start\",\"runId\":\"run-1\",\"workflow\":\"us-loop\"," +
                "\"startedAt\":\"2026-09-27T11:33:35.123Z\",\"namespaceId\":\"ns-1\"}",
            lines[0],
        )
        assertEquals(
            "{\"kind\":\"phase\",\"name\":\"preflight\",\"phaseKind\":\"code\"," +
                "\"status\":\"fail\",\"startedAt\":\"2026-09-27T11:33:35.123Z\"}",
            lines[1],
        )
        assertEquals("preflight", phase.name)
        assertEquals(run, registry.getCurrentRun())
    }

    @Test
    fun `createRun omits namespaceId when absent`() {
        val dir = Files.createTempDirectory("registry-no-namespace")
        val registry = registry(dir, id = "run-2")
        val run = registry.createRun("fix-loop")

        val line = Files.readAllLines(run.filePath)[0]
        assertEquals(
            "{\"kind\":\"run_start\",\"runId\":\"run-2\",\"workflow\":\"fix-loop\"," +
                "\"startedAt\":\"2026-09-27T11:33:35.123Z\"}",
            line,
        )
        assertFalse(line.contains("namespaceId"))
        assertNull(run.namespaceId)
    }

    @Test
    fun `passPhase and failPhase write byte-compatible phase_end records`() {
        val dir = Files.createTempDirectory("registry-phase-end")
        val registry = registry(dir)
        val run = registry.createRun("us-loop")
        val phase = registry.startPhase(run, "verify", PhaseKind.CODE)

        registry.passPhase(phase, mapOf("attempt" to 1))
        registry.failPhase(phase, mapOf("reason" to "boom"))

        val lines = Files.readAllLines(run.filePath)
        assertEquals(
            "{\"kind\":\"phase_end\",\"name\":\"verify\",\"status\":\"pass\",\"durationMs\":0," +
                "\"facts\":{\"attempt\":1}}",
            lines[2],
        )
        assertEquals(
            "{\"kind\":\"phase_end\",\"name\":\"verify\",\"status\":\"fail\",\"durationMs\":0," +
                "\"facts\":{\"reason\":\"boom\"}}",
            lines[3],
        )
    }

    @Test
    fun `endRun only writes facts when non-empty`() {
        val dir = Files.createTempDirectory("registry-run-end")
        val registry = registry(dir)
        val run = registry.createRun("us-loop")

        registry.endRun(run, RunStatus.FAIL)
        registry.endRun(run, RunStatus.PASS, mapOf("wroteNothing" to true))

        val lines = Files.readAllLines(run.filePath)
        assertEquals(
            "{\"kind\":\"run_end\",\"status\":\"fail\",\"durationMs\":0," +
                "\"endedAt\":\"2026-09-27T11:33:35.123Z\"}",
            lines[1],
        )
        assertEquals(
            "{\"kind\":\"run_end\",\"status\":\"pass\",\"durationMs\":0," +
                "\"endedAt\":\"2026-09-27T11:33:35.123Z\",\"facts\":{\"wroteNothing\":true}}",
            lines[2],
        )
    }

    @Test
    fun `endCurrentRunOnce writes at most once`() {
        val dir = Files.createTempDirectory("registry-once")
        val registry = registry(dir)
        registry.createRun("us-loop")

        assertTrue(registry.endCurrentRunOnce(RunStatus.FAIL))
        assertFalse(registry.endCurrentRunOnce(RunStatus.FAIL))
    }

    @Test
    fun `generated run id keeps the Node shape`() {
        val id = RunRegistry.defaultRunId(Instant.parse("2026-09-27T11:33:35.123Z"))
        assertTrue(Regex("^20260927T113335Z-[0-9a-f]{4}$").matches(id), "unexpected run id: $id")
    }
}
