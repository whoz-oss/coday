package io.whozoss.factory.verification.manifest

import io.whozoss.factory.verification.oracle.OracleExecutor
import java.nio.file.Path

/**
 * The resolved verification whitelist of one destination repository.
 *
 * Holds the parsed `factory/verification.json` manifest together with the
 * repository root, and is the single entry point to execute a declared
 * verification:
 *  - [resolveVerification] returns the declared entry, or a [VerificationResolution.NotDeclared]
 *    refusal (`VERIFICATION_NOT_DECLARED`) for any name absent from the whitelist;
 *  - [execute] runs the declared command through [OracleExecutor.runCommand] with
 *    `cwd` = repo root, a bounded timeout and a bounded output capture. The verdict
 *    is `exitCode == 0` — never derived from the content of the output.
 *
 * A name that is not declared is NOT executed: [execute] returns
 * [VerificationRunResult.NotDeclared] before spawning any process.
 */
class TargetRepoVerifications private constructor(
    val repoRoot: Path,
    val manifest: VerificationManifest,
    val defaultTimeoutMs: Long,
) {

    /** Resolves [name] against the whitelist, with the explicit refusal. */
    fun resolveVerification(name: String?): VerificationResolution {
        val entry = manifest.resolveVerification(name)
        return if (entry == null) {
            VerificationResolution.NotDeclared(name ?: "")
        } else {
            VerificationResolution.Declared(name!!, entry)
        }
    }

    /**
     * Executes the declared verification [name].
     *
     * @param timeoutOverrideMs an explicit timeout that wins over both the entry
     *   timeout and [defaultTimeoutMs] (used by callers that need a smoke timeout).
     */
    fun execute(name: String?, timeoutOverrideMs: Long? = null): VerificationRunResult {
        val resolved = manifest.resolveVerification(name)
            ?: return VerificationRunResult.NotDeclared(name ?: "")
        val timeoutMs = timeoutOverrideMs ?: resolved.effectiveTimeoutMs(defaultTimeoutMs)
        val result = OracleExecutor.runCommand(resolved.command, cwd = repoRoot, timeoutMs = timeoutMs)
        return VerificationRunResult.Executed(
            name = name!!,
            command = resolved.command,
            timeoutMs = timeoutMs,
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = result.durationMs,
            timedOut = result.timedOut,
        )
    }

    companion object {
        /** Loads the manifest of [repoRoot] (fail-closed on missing/invalid). */
        fun load(repoRoot: Path, defaultTimeoutMs: Long = DEFAULT_VERIFICATION_TIMEOUT_MS): TargetRepoVerifications {
            val normalized = repoRoot.toAbsolutePath().normalize()
            return TargetRepoVerifications(
                repoRoot = normalized,
                manifest = VerificationManifestParser.load(normalized),
                defaultTimeoutMs = defaultTimeoutMs,
            )
        }
    }
}

/** Outcome of executing a whitelisted verification. */
sealed interface VerificationRunResult {
    /** The command ran; [verdict] is `exitCode == 0` and not timed out. */
    data class Executed(
        val name: String,
        val command: String,
        val timeoutMs: Long,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean,
    ) : VerificationRunResult {
        val verdict: Boolean get() = exitCode == 0 && !timedOut
    }

    /** The name is not declared in `factory/verification.json`; nothing was executed. */
    data class NotDeclared(val name: String, val code: String = VERIFICATION_NOT_DECLARED) : VerificationRunResult
}
