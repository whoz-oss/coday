package io.whozoss.agentos.security

import mu.KLogging
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the correct [SecurityService] bean based on [SecurityConfigProperties.mode].
 *
 * - `local` (default): [LocalSecurityService] — OS username, no external dependencies.
 * - `auth`: [AuthSecurityService] — trusts the `X-External-User-Id` header set by the upstream gateway.
 *
 * Neither implementation depends on [io.whozoss.agentos.user.UserService]. User
 * persistence (lookup / auto-create) is handled by
 * [io.whozoss.agentos.user.UserService.resolveOrCreateByExternalId] at the callsite.
 */
@Configuration
@EnableConfigurationProperties(SecurityConfigProperties::class)
class SecurityConfiguration(
    private val props: SecurityConfigProperties,
) {
    @Bean
    fun securityService(): SecurityService {
        return when (props.mode) {
            SecurityMode.AUTH -> {
                logger.info { "[Security] Mode: auth (X-External-User-Id header)" }
                AuthSecurityService()
            }
            SecurityMode.LOCAL -> {
                logger.info { "[Security] Mode: local (OS username '${System.getProperty("user.name")}')" }
                LocalSecurityService()
            }
        }
    }

    companion object : KLogging()
}
