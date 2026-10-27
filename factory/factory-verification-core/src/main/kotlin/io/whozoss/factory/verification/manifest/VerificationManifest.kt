package io.whozoss.factory.verification.manifest

/**
 * Target-repository verification manifest — the `factory/verification.json`
 * whitelist of the *destination* repository (the product repo under validation),
 * NOT of `factory-verification-core`, of `factory-service` nor of a plugin.
 *
 * Convention (W8.2):
 * ```
 * <target_repo_root>/factory/verification.json
 * {
 *   "schemaVersion": "1",
 *   "verifications": {
 *     "forge-frontend-verification": {
 *       "command": "./factory/verification/forge-frontend-verification",
 *       "timeoutMs": 1800000
 *     }
 *   }
 * }
 * ```
 * The scripts themselves live under `<target_repo_root>/factory/verification/<name>`.
 *
 * The manifest is a **trust boundary**: it is both the resolution table and the
 * whitelist. Only a verification `name` declared here may be executed; any other
 * name yields [VERIFICATION_NOT_DECLARED]. Nothing in the core knows a
 * development verification name.
 *
 * This module stays a pure Kotlin/JVM library: the manifest model and parser use
 * only the Kotlin standard library, the JDK and the already-present light JSON
 * library (Jackson). No Spring, no HTTP client, no database driver.
 */

/** The only supported manifest schema version. */
const val VERIFICATION_MANIFEST_SCHEMA_VERSION = "1"

/** Repository-relative location of the manifest, from the destination repo root. */
const val VERIFICATION_MANIFEST_RELATIVE_PATH = "factory/verification.json"

/** Default command timeout when a [VerificationEntry] declares none (30 minutes). */
const val DEFAULT_VERIFICATION_TIMEOUT_MS: Long = 1_800_000L

/** Error code returned when a name is absent from the whitelist. */
const val VERIFICATION_NOT_DECLARED = "VERIFICATION_NOT_DECLARED"

/** Error code when `<repo_root>/factory/verification.json` is absent. */
const val VERIFICATION_MANIFEST_MISSING = "VERIFICATION_MANIFEST_MISSING"

/** Error code when the manifest exists but is not a valid whitelist. */
const val VERIFICATION_MANIFEST_INVALID = "VERIFICATION_MANIFEST_INVALID"

/** One whitelisted verification: the command to run and its optional timeout. */
data class VerificationEntry(
    val command: String,
    val timeoutMs: Long? = null,
) {
    /** Resolves the effective timeout: the declared one, else [defaultTimeoutMs]. */
    fun effectiveTimeoutMs(defaultTimeoutMs: Long = DEFAULT_VERIFICATION_TIMEOUT_MS): Long =
        timeoutMs?.takeIf { it > 0 } ?: defaultTimeoutMs
}

/** The parsed `factory/verification.json` whitelist. */
data class VerificationManifest(
    val schemaVersion: String = VERIFICATION_MANIFEST_SCHEMA_VERSION,
    val verifications: Map<String, VerificationEntry> = emptyMap(),
) {
    /**
     * Resolves a verification name against the whitelist.
     *
     * @return the declared [VerificationEntry], or `null` when the name is not
     *   declared (or blank). `null` is the trust-boundary signal that the name is
     *   NOT executable.
     */
    fun resolveVerification(name: String?): VerificationEntry? =
        if (name.isNullOrBlank()) null else verifications[name]
}

/** Explicit resolution result, carrying the [VERIFICATION_NOT_DECLARED] refusal. */
sealed interface VerificationResolution {
    data class Declared(val name: String, val entry: VerificationEntry) : VerificationResolution
    data class NotDeclared(val name: String, val code: String = VERIFICATION_NOT_DECLARED) : VerificationResolution
}

/** Thrown when the manifest is missing or invalid. */
class VerificationManifestException(
    val code: String,
    message: String,
) : RuntimeException(message)
