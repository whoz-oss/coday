package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
import mu.KotlinLogging
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

/**
 * Logs a loud startup banner when `factory.security.insecure-mode` is enabled.
 * No-op otherwise.
 */
@Component
class InsecureModeWarning(
    private val properties: FactoryProperties,
) : InitializingBean {

    private val logger = KotlinLogging.logger {}

    override fun afterPropertiesSet() {
        if (!properties.security.insecureMode) return
        logger.warn {
            """
            |
            |############################################################
            |#  FACTORY INSECURE MODE IS ENABLED (FACTORY_INSECURE_MODE) #
            |#  Authentication is DISABLED: every caller, from ANY       #
            |#  network address, acts as the insecure-mode-user, even    #
            |#  with an invalid token.                                   #
            |#  Never use this in a shared or production deployment.     #
            |############################################################
            """.trimMargin()
        }
    }
}
