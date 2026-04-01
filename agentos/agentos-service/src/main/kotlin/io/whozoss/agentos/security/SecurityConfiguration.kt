package io.whozoss.agentos.security

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the correct [SecurityService] bean based on [SecurityConfigProperties.mode].
 *
 * - `local` (default): [LocalSecurityService] — OS username, auto-creates User on first access.
 * - `auth`: [AuthSecurityService] — Cloudflare JWT / x-forwarded-email, auto-creates User on first access.
 */
@Configuration
@EnableConfigurationProperties(SecurityConfigProperties::class)
class SecurityConfiguration(
    private val props: SecurityConfigProperties,
) {
    @Bean
    fun securityService(
        userService: UserService,
        objectMapper: ObjectMapper,
    ): SecurityService {
        return when (props.mode) {
            SecurityMode.AUTH -> {
                logger.info { "[Security] Mode: auth (Cloudflare JWT / x-forwarded-email)" }
                AuthSecurityService(userService, objectMapper)
            }
            SecurityMode.LOCAL -> {
                logger.info { "[Security] Mode: local (OS username '${System.getProperty("user.name")}')" }
                LocalSecurityService(userService)
            }
        }
    }

    companion object : KLogging()
}
