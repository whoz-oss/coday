package io.whozoss.factory.verification.manifest

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic tests of the destination-repository verification manifest
 * (`factory/verification.json`) and its whitelist execution.
 *
 * No Spring, no database, no network: a temporary repository root with real,
 * executable shell fixtures drives the end-to-end resolution -> execution path.
 */
class VerificationManifestTest {

    @TempDir
    lateinit var repoRoot: Path

    // ------------------------------------------------------------------ helpers

    private fun writeManifest(json: String): Path {
        val dir = repoRoot.resolve("factory")
        Files.createDirectories(dir)
        return dir.resolve("verification.json").also { Files.writeString(it, json.trimIndent(), StandardCharsets.UTF_8) }
    }

    /** Writes an executable script under `factory/verification/<name>`. */
    private fun writeScript(name: String, body: String): Path {
        val dir = repoRoot.resolve("factory/verification")
        Files.createDirectories(dir)
        val script = dir.resolve(name)
        Files.writeString(script, "#!/bin/sh\n$body\n", StandardCharsets.UTF_8)
        check(script.toFile().setExecutable(true)) { "could not mark $script executable" }
        return script
    }

    private fun manifestOf(name: String, command: String, timeoutMs: Long? = null): String {
        val timeout = if (timeoutMs == null) "" else ", \"timeoutMs\": $timeoutMs"
        return """
            {
              "schemaVersion": "1",
              "verifications": {
                "$name": { "command": "$command"$timeout }
              }
            }
        """.trimIndent()
    }

    // -------------------------------------------------------------- parsing

    @Test
    fun `parses a valid manifest and exposes its entries`() {
        val manifest = VerificationManifestParser.parse(
            """
            {
              "schemaVersion": "1",
              "verifications": {
                "forge-frontend-verification": {
                  "command": "./factory/verification/forge-frontend-verification",
                  "timeoutMs": 1800000
                },
                "unit": { "command": "pnpm test" }
              }
            }
            """.trimIndent(),
        )

        assertEquals("1", manifest.schemaVersion)
        assertEquals(2, manifest.verifications.size)
        assertEquals(
            VerificationEntry("./factory/verification/forge-frontend-verification", 1800000),
            manifest.resolveVerification("forge-frontend-verification"),
        )
        assertNull(manifest.resolveVerification("unit")!!.timeoutMs)
    }

    @Test
    fun `loads the manifest from the destination repo root`() {
        writeManifest(manifestOf("smoke", "./factory/verification/smoke"))
        val loaded = VerificationManifestParser.load(repoRoot)
        assertEquals("1", loaded.schemaVersion)
        assertTrue(loaded.verifications.containsKey("smoke"))
    }

    @Test
    fun `a missing manifest is fail-closed`() {
        val failure = assertFailsWith<VerificationManifestException> { VerificationManifestParser.load(repoRoot) }
        assertEquals(VERIFICATION_MANIFEST_MISSING, failure.code)
    }

    @Test
    fun `rejects an invalid schema version`() {
        val json = """{ "schemaVersion": "2", "verifications": {} }"""
        val failure = assertFailsWith<VerificationManifestException> { VerificationManifestParser.parse(json) }
        assertEquals(VERIFICATION_MANIFEST_INVALID, failure.code)
    }

    @Test
    fun `rejects an entry without a command`() {
        val json = """{ "schemaVersion": "1", "verifications": { "x": { "timeoutMs": 10 } } }"""
        val failure = assertFailsWith<VerificationManifestException> { VerificationManifestParser.parse(json) }
        assertEquals(VERIFICATION_MANIFEST_INVALID, failure.code)
    }

    @Test
    fun `rejects an unknown field inside an entry`() {
        val json = """{ "schemaVersion": "1", "verifications": { "x": { "command": "true", "shell": true } } }"""
        val failure = assertFailsWith<VerificationManifestException> { VerificationManifestParser.parse(json) }
        assertEquals(VERIFICATION_MANIFEST_INVALID, failure.code)
    }

    // ------------------------------------------------------------ resolution

    @Test
    fun `an undeclared name is not resolvable and not executable`() {
        writeManifest(manifestOf("smoke", "./factory/verification/smoke"))
        val target = TargetRepoVerifications.load(repoRoot)

        assertNull(target.manifest.resolveVerification("missing"))
        assertTrue(target.resolveVerification("missing") is VerificationResolution.NotDeclared)
        assertEquals(VERIFICATION_NOT_DECLARED, (target.resolveVerification("missing") as VerificationResolution.NotDeclared).code)
        assertEquals(VerificationRunResult.NotDeclared("missing"), target.execute("missing"))
    }

    @Test
    fun `the whitelist blocks a script that exists but is not declared`() {
        // The script exists on disk and would create a marker if run, but the
        // manifest does not declare it: it must never be executed.
        val marker = repoRoot.resolve("executed.marker")
        writeScript("undeclared", "touch '${marker.toAbsolutePath()}'")
        writeManifest(manifestOf("smoke", "./factory/verification/smoke"))

        val target = TargetRepoVerifications.load(repoRoot)
        val result = target.execute("undeclared")

        assertEquals(VerificationRunResult.NotDeclared("undeclared"), result)
        assertFalse(Files.exists(marker), "an undeclared verification must not be executed")
    }

    @Test
    fun `effective timeout falls back to the default when none is declared`() {
        assertEquals(5_000L, VerificationEntry("cmd").effectiveTimeoutMs(5_000L))
        assertEquals(DEFAULT_VERIFICATION_TIMEOUT_MS, VerificationEntry("cmd").effectiveTimeoutMs())
    }

    @Test
    fun `effective timeout uses the declared value when present`() {
        assertEquals(42L, VerificationEntry("cmd", timeoutMs = 42L).effectiveTimeoutMs(5_000L))
    }

    // ------------------------------------------------------------- execution

    @Test
    fun `executes a declared passing fixture end-to-end`() {
        writeScript("pass", "exit 0")
        writeManifest(manifestOf("pass", "./factory/verification/pass"))

        val result = TargetRepoVerifications.load(repoRoot).execute("pass")
        assertTrue(result is VerificationRunResult.Executed)
        val executed: VerificationRunResult.Executed = result
        assertEquals(0, executed.exitCode)
        assertFalse(executed.timedOut)
        assertTrue(executed.verdict)
    }

    @Test
    fun `executes a declared failing fixture end-to-end`() {
        writeScript("fail", "exit 3")
        writeManifest(manifestOf("fail", "./factory/verification/fail"))

        val executed = TargetRepoVerifications.load(repoRoot).execute("fail") as VerificationRunResult.Executed
        assertEquals(3, executed.exitCode)
        assertFalse(executed.verdict)
    }

    @Test
    fun `applies the default timeout when the entry declares none`() {
        writeScript("slow", "sleep 5")
        writeManifest(manifestOf("slow", "./factory/verification/slow"))

        val executed = TargetRepoVerifications.load(repoRoot, defaultTimeoutMs = 200).execute("slow")
                as VerificationRunResult.Executed
        assertTrue(executed.timedOut)
        assertEquals(200L, executed.timeoutMs)
        assertFalse(executed.verdict)
    }

    @Test
    fun `the entry timeout wins over the default timeout`() {
        writeScript("quick", "sleep 1; exit 0")
        writeManifest(manifestOf("quick", "./factory/verification/quick", timeoutMs = 10_000))

        val executed = TargetRepoVerifications.load(repoRoot, defaultTimeoutMs = 200).execute("quick")
                as VerificationRunResult.Executed
        assertFalse(executed.timedOut)
        assertEquals(10_000L, executed.timeoutMs)
        assertTrue(executed.verdict)
    }

    @Test
    fun `an explicit override wins over the entry timeout`() {
        writeScript("slow", "sleep 5")
        writeManifest(manifestOf("slow", "./factory/verification/slow", timeoutMs = 10_000))

        val executed = TargetRepoVerifications.load(repoRoot).execute("slow", timeoutOverrideMs = 200)
                as VerificationRunResult.Executed
        assertTrue(executed.timedOut)
        assertEquals(200L, executed.timeoutMs)
    }
}
