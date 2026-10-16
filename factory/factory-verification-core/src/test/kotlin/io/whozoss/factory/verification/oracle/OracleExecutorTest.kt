package io.whozoss.factory.verification.oracle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic oracle-executor tests, in the spirit of the Node fixtures under
 * `factory/tests/fixtures/oracle-process/{pass,fail,timeout,empty,large-output}`.
 * No database, no Testcontainers, no network.
 */
class OracleExecutorTest {

    private val workDir: Path = Path.of(".").toAbsolutePath().normalize()

    private fun oracle(vararg argv: String, timeoutMs: Long = 60_000, requireWork: Boolean = true) =
        OracleExecutionDefinition(argv = argv.toList(), timeoutMs = timeoutMs, requireWork = requireWork)

    // ---------------------------------------------------------------- runCommand

    @Test
    fun `runCommand returns exit code 0 for a successful shell command`() {
        val result = OracleExecutor.runCommand("echo hello", cwd = workDir, timeoutMs = 10_000)
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.stdout.contains("hello"), "stdout was: ${result.stdout}")
    }

    @Test
    fun `runCommand surfaces a non-zero exit code`() {
        val result = OracleExecutor.runCommand("exit 7", cwd = workDir, timeoutMs = 10_000)
        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun `runCommand reports and kills on timeout`() {
        val result = OracleExecutor.runCommand("sleep 30", cwd = workDir, timeoutMs = 300)
        assertTrue(result.timedOut, "expected a timeout")
        assertEquals(-1, result.exitCode)
    }

    // -------------------------------------------------------------- executeOracle

    @Test
    fun `pass fixture yields CLEAN and pass`() {
        val result = OracleExecutor.executeOracle(
            oracle("sh", "-c", "echo '> Task :factory:oracle-smoke'"),
            repoRoot = workDir,
        )
        assertEquals(OracleClassification.CLEAN, result.classification)
        assertEquals(OracleOutcome.PASS, result.outcome)
        assertEquals(0, result.exitCode)
        assertEquals(1, result.counts.executed)
        assertFalse(result.stdout.truncated)
    }

    @Test
    fun `fail fixture yields PRODUCT_REGRESSION and fail`() {
        val result = OracleExecutor.executeOracle(
            oracle("sh", "-c", "echo 'deterministic product failure' 1>&2; exit 7"),
            repoRoot = workDir,
        )
        assertEquals(OracleClassification.PRODUCT_REGRESSION, result.classification)
        assertEquals(OracleOutcome.FAIL, result.outcome)
        assertEquals(7, result.exitCode)
    }

    @Test
    fun `timeout fixture yields ORACLE_INFRASTRUCTURE and indeterminate`() {
        val result = OracleExecutor.executeOracle(
            oracle("sh", "-c", "sleep 30", timeoutMs = 300),
            repoRoot = workDir,
        )
        assertEquals(OracleClassification.ORACLE_INFRASTRUCTURE, result.classification)
        assertEquals(OracleOutcome.INDETERMINATE, result.outcome)
        assertTrue(result.timedOut)
    }

    @Test
    fun `empty fixture yields EMPTY_SUCCESS when requireWork is true`() {
        val result = OracleExecutor.executeOracle(
            oracle("sh", "-c", "true", requireWork = true),
            repoRoot = workDir,
        )
        assertEquals(OracleClassification.EMPTY_SUCCESS, result.classification)
        assertEquals(OracleOutcome.INDETERMINATE, result.outcome)
        assertEquals(0, result.exitCode)
        assertEquals(0, result.counts.executed)
    }

    @Test
    fun `empty fixture is CLEAN when requireWork is false`() {
        val result = OracleExecutor.executeOracle(
            oracle("sh", "-c", "true", requireWork = false),
            repoRoot = workDir,
        )
        assertEquals(OracleClassification.CLEAN, result.classification)
        assertEquals(OracleOutcome.PASS, result.outcome)
    }

    @Test
    fun `large output is bounded and flagged truncated`() {
        val result = OracleExecutor.executeOracle(
            oracle(
                "sh",
                "-c",
                "echo '> Task :factory:large-output'; " +
                    "yes O | head -n 20000; " +
                    "yes E | head -n 20000 1>&2",
            ),
            repoRoot = workDir,
        )
        assertTrue(result.stdout.truncated)
        assertTrue(result.stderr.truncated)
        assertEquals(OracleExecutor.BOUNDED_LIMIT, result.stdout.excerpt.length)
        assertEquals(OracleExecutor.BOUNDED_LIMIT, result.stderr.excerpt.length)
        // The task marker is the first line, so it is within the excerpt.
        assertEquals(1, result.counts.executed)
    }

    @Test
    fun `spawn error yields ORACLE_INFRASTRUCTURE`() {
        val result = OracleExecutor.executeOracle(
            oracle("/nonexistent/binary/definitely-not-there"),
            repoRoot = workDir,
        )
        assertEquals(OracleClassification.ORACLE_INFRASTRUCTURE, result.classification)
        assertEquals(OracleOutcome.INDETERMINATE, result.outcome)
        assertNotNull(result.spawnError)
    }

    // -------------------------------------------------------------- classification

    @Test
    fun `classification order — infrastructure outranks non-zero exit`() {
        val definition = oracle("x")
        val observation = OracleExecutionObservation(
            exitCode = 1,
            signal = null,
            timedOut = true,
            spawnError = null,
            counts = TaskOutcomeCounter.countTaskOutcomes(""),
        )
        val c = OracleExecutor.classifyOracleExecution(definition, observation)
        assertEquals(OracleClassification.ORACLE_INFRASTRUCTURE, c.classification)
    }

    @Test
    fun `classification order — non-zero exit outranks empty success`() {
        val definition = oracle("x", requireWork = true)
        val observation = OracleExecutionObservation(
            exitCode = 2,
            signal = null,
            timedOut = false,
            spawnError = null,
            counts = TaskOutcomeCounter.countTaskOutcomes(""),
        )
        val c = OracleExecutor.classifyOracleExecution(definition, observation)
        assertEquals(OracleClassification.PRODUCT_REGRESSION, c.classification)
        assertEquals(OracleOutcome.FAIL, c.outcome)
    }

    // ----------------------------------------------------------------- artifacts

    @Test
    fun `oracle artifact hashes its raw bounded output`() {
        val result = OracleExecutor.executeOracle(
            oracle("sh", "-c", "echo '> Task :x'"),
            repoRoot = workDir,
        )
        val artifact = OracleExecutor.oracleArtifact(result)
        assertTrue(artifact.hash.startsWith("sha256:"))
        assertEquals(64, artifact.hash.removePrefix("sha256:").length)
        assertTrue(artifact.raw.contains("stdout"))
    }

    @Test
    fun `validateOracleRoot rejects a relative path`() {
        assertFailsWith<InvalidOracleRootException> { OracleExecutor.validateOracleRoot("relative/path") }
        assertFailsWith<InvalidOracleRootException> { OracleExecutor.validateOracleRoot(null) }
    }

    @Test
    fun `validateOracleRoot accepts an absolute path`() {
        val resolved = OracleExecutor.validateOracleRoot(workDir.toString())
        assertTrue(resolved.isAbsolute)
        assertTrue(Files.exists(resolved))
    }
}
