package io.whozoss.agentos.config

import io.whozoss.agentos.security.SecurityService
import io.whozoss.agentos.user.UserRepository
import org.springframework.data.domain.AuditorAware
import org.springframework.stereotype.Component
import java.util.Optional

/**
 * Resolves the current user's UUID for Spring Data `@CreatedBy` / `@LastModifiedBy` annotations.
 *
 * Relies on [SecurityService] which reads from [org.springframework.web.context.request.RequestContextHolder].
 * Returns [Optional.empty] when no HTTP request context is available (e.g. background coroutines,
 * application startup), leaving audit fields as null.
 *
 * Currently only [io.whozoss.agentos.agentConfig.AgentConfigNode] uses auditing annotations;
 * that entity is always manipulated from an HTTP request context.
 */
@Component
class AgentOsAuditorAware(
    private val securityService: SecurityService,
    private val userRepository: UserRepository,
) : AuditorAware<String> {
    override fun getCurrentAuditor(): Optional<String> =
        runCatching {
            val externalId = securityService.resolveCurrentIdentity()
            userRepository.findByExternalId(externalId)?.id?.toString()
        }.getOrNull()
            ?.let { Optional.of(it) }
            ?: Optional.empty()
}
