package io.whozoss.factory.settings

import io.whozoss.factory.ci.GitHubChecksClient
import io.whozoss.factory.ci.GitHubChecksProperties
import io.whozoss.factory.ci.GitHubTokenVerification
import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

/**
 * What the Admin page shows about the GitHub token. It never carries the token: only a masked hint.
 *
 * [status] is `connected`, `invalid`, `unconfigured` or `unreachable`; [source] is `admin`, `environment`, `gh-cli`
 * or `none` (see [GitHubChecksProperties.tokenSource]).
 */
data class FactoryGitHubSettingsDto(
    val configured: Boolean,
    val connected: Boolean,
    val status: String,
    val source: String,
    val login: String? = null,
    val maskedToken: String? = null,
    val message: String? = null,
    val checkedAt: String? = null,
)

/**
 * The GitHub token an administrator sets from the cockpit Admin page, used by the deterministic `github-pr-checks` step.
 *
 * It is persisted in the `:FactorySetting` node `github.token` (the same node family as the run-concurrency limit),
 * sealed by [SecretCipher] when `factory.encryption.*` is configured, and mirrored in memory in
 * [GitHubChecksProperties.adminToken] so that a CI poll never reads the database. It is loaded once at startup
 * (fail-soft: a database error leaves the environment / `gh` token in charge) and updated by [save] / [clear],
 * whose in-memory value changes only after the write succeeded.
 *
 * The token is never logged and never returned: [status] reports a verified login and a masked hint only.
 */
@Service
class GitHubTokenSettingsService(
    private val neo4jClient: Neo4jClient,
    private val properties: GitHubChecksProperties,
    private val client: GitHubChecksClient,
    private val cipher: SecretCipher,
) : ApplicationRunner {

    private val logger = KotlinLogging.logger {}

    /** Last live verification, keyed by a fingerprint of the token so that a changed token is never served stale. */
    private class Verified(val fingerprint: String, val settings: FactoryGitHubSettingsDto, val atMillis: Long)

    @Volatile
    private var cache: Verified? = null

    /** Replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    override fun run(args: ApplicationArguments) {
        loadPersistedToken()
    }

    /** Reads the stored token into memory. Fail-soft: on any error the other sources stay in charge. */
    @Synchronized
    fun loadPersistedToken() {
        val stored = runCatching { readStoredValue() }
            .onFailure { logger.warn { "Could not read the GitHub token setting: ${it.javaClass.simpleName}" } }
            .getOrNull()
        val token = stored?.let(cipher::decrypt)?.takeIf { it.isNotBlank() }
        if (stored != null && token == null) {
            logger.warn { "The stored GitHub token cannot be opened (encryption key changed?): it is ignored" }
        }
        properties.adminToken = token
        cache = null
    }

    /**
     * The status of the effective token. A configured token is verified against GitHub; the answer is reused for
     * [CACHE_TTL_MILLIS] while the token and its source are unchanged, so refreshing the page stays snappy.
     */
    fun status(forceVerify: Boolean = false): FactoryGitHubSettingsDto {
        val token = properties.effectiveToken
        val source = properties.tokenSource
        if (token.isBlank()) return unconfigured()
        val fingerprint = fingerprint(source, token)
        val cached = cache
        if (!forceVerify && cached != null && cached.fingerprint == fingerprint && clock() - cached.atMillis < CACHE_TTL_MILLIS) {
            return cached.settings
        }
        return verified(token, source, fingerprint)
    }

    /**
     * Verifies [rawToken] live and, unless GitHub rejects it, makes it the admin token (it wins over the environment).
     * A rejected token is NOT saved and the previous one stays active: the answer then has status `invalid`.
     * When GitHub cannot be reached the token is saved anyway (offline installs) with status `unreachable`.
     *
     * @throws InvalidGitHubTokenException when the token is blank, too long or contains whitespace.
     */
    @Synchronized
    fun save(rawToken: String?, actorId: String): FactoryGitHubSettingsDto {
        val token = rawToken?.trim().orEmpty()
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH || token.any { it.isWhitespace() || it.isISOControl() }) {
            throw InvalidGitHubTokenException()
        }
        val source = GitHubChecksProperties.SOURCE_ADMIN
        val fingerprint = fingerprint(source, token)
        val result = verify(token)
        if (result is GitHubTokenVerification.Invalid) {
            logger.info { "GitHub token rejected by GitHub, not saved (by $actorId)" }
            return dto(result, properties.maskedHint(token), source)
        }
        neo4jClient.query(
            "MERGE (s:FactorySetting {id: \$id}) " +
                "SET s.stringValue = \$value, s.updatedAt = \$updatedAt, s.updatedBy = \$updatedBy " +
                "RETURN s.id AS id",
        ).bindAll(
            mapOf("id" to SETTING_ID, "value" to cipher.encrypt(token), "updatedAt" to Instant.now().toString(), "updatedBy" to actorId),
        ).fetch().one().orElseThrow()
        properties.adminToken = token
        val settings = dto(result, properties.maskedHint(token), source)
        cache = Verified(fingerprint, settings, clock())
        logger.info { "GitHub token set from the Admin page by $actorId (${settings.status})" }
        return settings
    }

    /** Forgets the admin token: the environment / `gh` token, if any, takes over. */
    @Synchronized
    fun clear(actorId: String): FactoryGitHubSettingsDto {
        neo4jClient.query(
            "MERGE (s:FactorySetting {id: \$id}) " +
                "REMOVE s.stringValue " +
                "SET s.updatedAt = \$updatedAt, s.updatedBy = \$updatedBy " +
                "RETURN s.id AS id",
        ).bindAll(
            mapOf("id" to SETTING_ID, "updatedAt" to Instant.now().toString(), "updatedBy" to actorId),
        ).fetch().one().orElseThrow()
        properties.adminToken = null
        cache = null
        logger.info { "GitHub token cleared from the Admin page by $actorId" }
        return status()
    }

    private fun verified(token: String, source: String, fingerprint: String): FactoryGitHubSettingsDto {
        val settings = dto(verify(token), properties.maskedHint(token), source)
        cache = Verified(fingerprint, settings, clock())
        return settings
    }

    private fun verify(token: String): GitHubTokenVerification =
        runCatching { client.verifyToken(token) }
            .getOrElse { GitHubTokenVerification.Unreachable("Could not reach GitHub (${it.javaClass.simpleName}).") }

    private fun dto(result: GitHubTokenVerification, masked: String, source: String): FactoryGitHubSettingsDto {
        val checkedAt = Instant.ofEpochMilli(clock()).toString()
        return when (result) {
            is GitHubTokenVerification.Connected -> FactoryGitHubSettingsDto(
                configured = true, connected = true, status = "connected", source = source,
                login = result.login, maskedToken = masked, checkedAt = checkedAt,
            )
            is GitHubTokenVerification.Invalid -> FactoryGitHubSettingsDto(
                configured = true, connected = false, status = "invalid", source = source,
                maskedToken = masked, message = result.message, checkedAt = checkedAt,
            )
            is GitHubTokenVerification.Unreachable -> FactoryGitHubSettingsDto(
                configured = true, connected = false, status = "unreachable", source = source,
                maskedToken = masked, message = result.message, checkedAt = checkedAt,
            )
        }
    }

    private fun unconfigured() = FactoryGitHubSettingsDto(
        configured = false, connected = false, status = "unconfigured", source = GitHubChecksProperties.SOURCE_NONE,
        message = "No GitHub token is configured.",
    )

    private fun readStoredValue(): String? =
        neo4jClient.query("MATCH (s:FactorySetting {id: \$id}) RETURN s.stringValue AS value")
            .bind(SETTING_ID).to("id").fetch().one().orElse(null)?.get("value") as? String

    /** A one-way fingerprint: the cache never holds the token itself. */
    private fun fingerprint(source: String, token: String): String =
        MessageDigest.getInstance("SHA-256").digest("$source:$token".toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val SETTING_ID = "github.token"
        const val MAX_TOKEN_LENGTH = 512
        const val CACHE_TTL_MILLIS = 30_000L
    }
}
