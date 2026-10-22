package io.whozoss.factory.web.security

import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.config.SecurityMode
import mu.KotlinLogging
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Startup checks and notice of the security mode.
 *
 *  - Fails startup when the removed `FACTORY_INSECURE_MODE` / `factory.security.insecure-mode`
 *    is still set, to any value: ignoring it would silently turn an explicit `false`
 *    (secure) into the open `local` default.
 *  - Logs the active mode.
 *  - In `local` mode on a non-loopback bind, logs a loud warning (startup is not refused):
 *    every caller reaching Factory acts as the OS user.
 */
@Component
class SecurityModeBanner(
    private val environment: Environment,
    private val properties: FactoryProperties,
) : InitializingBean {

    private val logger = KotlinLogging.logger {}

    override fun afterPropertiesSet() {
        val mode = properties.security.mode
        logger.info { "[Security] Factory security mode: ${mode.name.lowercase()}" }
        val host = properties.bind.host
        if (mode == SecurityMode.LOCAL && !isLoopback(host)) {
            logger.warn {
                """
                |
                |##################################################################
                |#  LOCAL security mode on a non-loopback bind ('$host'):         #
                |#  every caller acts as the OS user, with or without a token.   #
                |#  Use FACTORY_SECURITY_MODE=auth behind an authenticating      #
                |#  proxy, or bind to 127.0.0.1.                                 #
                |##################################################################
                """.trimMargin()
            }
        }
    }

    companion object {
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "[::1]", "localhost")

        fun isLoopback(host: String): Boolean = host.trim().lowercase() in LOOPBACK_HOSTS
    }
}
