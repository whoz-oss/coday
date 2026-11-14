package io.whozoss.agentos.messaging

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-use tokens put in a link to a messaging application: a signed-in user asks for one, opens it, and the
 * bot that receives it learns what the user asked for ([T]).
 *
 * Kept in memory: a token lives a few minutes and is worth nothing once used, so a restart only costs the user
 * a new click. AgentOS runs a single instance (case runtimes are in memory too), so every bot sees the tokens.
 * Only a hash of each token is held.
 *
 * A token is 32 random bytes in URL-safe base64 without padding: 43 characters of `A-Z a-z 0-9 _ -`, which
 * fits the 64-character `/start` parameter of Telegram.
 */
class SingleUseTokens<T : Any>(
    private val ttl: Duration,
    private val clock: Clock,
) {
    private val pending = ConcurrentHashMap<String, Pending<T>>()
    private val random = SecureRandom()

    /** Issues a token for [value], dropping the pending tokens whose value [replaces] matches. */
    fun issue(
        value: T,
        replaces: (T) -> Boolean = { false },
    ): IssuedLinkToken {
        val now = clock.instant()
        pending.entries.removeIf { (_, entry) -> entry.expiresAt <= now || replaces(entry.value) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        val expiresAt = now.plus(ttl)
        pending[hash(token)] = Pending(value = value, expiresAt = expiresAt)
        return IssuedLinkToken(token = token, expiresAt = expiresAt)
    }

    /** Uses [token] up and returns its value, or null when it is unknown or expired. A token works once. */
    fun consume(token: String): T? =
        pending
            .remove(hash(token))
            ?.takeIf { it.expiresAt > clock.instant() }
            ?.value

    private fun hash(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray())
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private data class Pending<T>(
        val value: T,
        val expiresAt: Instant,
    )

    private companion object {
        const val TOKEN_BYTES = 32
    }
}

/** A single-use token as returned to the user who asked for it. */
data class IssuedLinkToken(
    val token: String,
    val expiresAt: Instant,
)
