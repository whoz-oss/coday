package io.whozoss.factory.tools

import io.whozoss.factory.web.FakeIdp
import java.time.Duration

/**
 * Command-line helper that mints a long-lived Factory HS256 JWT, to paste into an
 * AgentOS Auth Setting (the bearer of the `FACTORY_WORKER` / `FACTORY_WORKSTREAM`
 * HTTP_API integrations).
 *
 * Run it with `./gradlew :factory-service:mintToken -Psub=<user> -PexpiryDays=<n>`
 * (see `factory/tools/FACTORY_WORKSTREAM_HTTP_API.md`). The signing secret is
 * `FACTORY_FAKE_IDP_SECRET`, i.e. the same value as `factory.security.fake-idp-secret`.
 */
object MintToken {

    private const val DEFAULT_EXPIRY_DAYS = 365L

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList().chunked(2).associate { it[0] to it.getOrNull(1) }
        val subject = options["--sub"]?.takeIf { it.isNotBlank() }
            ?: fail("--sub <user id> is required")
        val expiryDays = options["--expiry-days"]?.toLongOrNull() ?: DEFAULT_EXPIRY_DAYS
        if (expiryDays <= 0) fail("--expiry-days must be positive")
        val secret = System.getenv("FACTORY_FAKE_IDP_SECRET")?.takeIf { it.isNotEmpty() }
            ?: fail("FACTORY_FAKE_IDP_SECRET must be set to the secret the Factory server uses")
        println(token(subject, Duration.ofDays(expiryDays), secret))
    }

    /** Mints a JWT for [subject] that expires after [lifetime]. */
    fun token(subject: String, lifetime: Duration, secret: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String =
        FakeIdp.issueJwt(
            mapOf(
                "sub" to subject,
                "iat" to nowSeconds,
                "exp" to nowSeconds + lifetime.seconds,
            ),
            secret,
        )

    private fun fail(message: String): Nothing {
        System.err.println("mintToken: $message")
        kotlin.system.exitProcess(2)
    }
}
