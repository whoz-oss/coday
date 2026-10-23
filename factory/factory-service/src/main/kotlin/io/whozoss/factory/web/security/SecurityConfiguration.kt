package io.whozoss.factory.web.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.config.SecurityMode
import io.whozoss.factory.web.TokenVerifier
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the [IdentityResolver] selected by `factory.security.mode`
 * (`FACTORY_SECURITY_MODE`, shared with AgentOS):
 *
 *  - `local` (default): [LocalIdentityResolver], the OS username;
 *  - `auth`: [AuthIdentityResolver], a Factory JWT or the upstream proxy headers.
 *
 * Mirrors `io.whozoss.agentos.security.SecurityConfiguration` of `agentos-service`.
 */
@Configuration
class SecurityConfiguration(
    private val properties: FactoryProperties,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun identityResolver(tokenVerifier: TokenVerifier, objectMapper: ObjectMapper): IdentityResolver =
        when (properties.security.mode) {
            SecurityMode.AUTH -> {
                logger.info {
                    "[Security] Mode: auth (Factory JWT / X-External-User-Id / Cloudflare JWT / " +
                        "bearer preferred_username / x-forwarded-email)"
                }
                AuthIdentityResolver(tokenVerifier, objectMapper)
            }
            SecurityMode.LOCAL -> {
                logger.info { "[Security] Mode: local (OS username '${LocalIdentityResolver.systemOsUsername()}')" }
                LocalIdentityResolver()
            }
        }
}
