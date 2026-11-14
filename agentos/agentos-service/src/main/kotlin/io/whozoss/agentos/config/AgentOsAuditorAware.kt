package io.whozoss.agentos.config

import io.whozoss.agentos.security.SecurityService
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import io.whozoss.agentos.user.UserRepository
import org.springframework.data.domain.AuditorAware
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.Optional

/**
 * Resolves the current user's UUID for Spring Data `@CreatedBy` / `@LastModifiedBy` annotations.
 *
 * The user authenticated on the thread when there is one ([AgentOsAuthentication]: the request's, or the
 * one a messaging chat acts for), else the one [SecurityService] resolves from
 * [org.springframework.web.context.request.RequestContextHolder]. Returns [Optional.empty] when neither
 * is available (e.g. background coroutines, application startup), leaving audit fields as null.
 *
 * Currently only [io.whozoss.agentos.agentConfig.AgentConfigNode] uses auditing annotations: an HTTP
 * request, or a configuration plan applied from a chat, changes it.
 */
@Component
class AgentOsAuditorAware(
    private val securityService: SecurityService,
    private val userRepository: UserRepository,
) : AuditorAware<String> {
    override fun getCurrentAuditor(): Optional<String> =
        (SecurityContextHolder.getContext().authentication as? AgentOsAuthentication)?.let { Optional.of(it.name) }
            ?: requestAuditor()

    private fun requestAuditor(): Optional<String> =
        runCatching {
            val externalId = securityService.resolveCurrentIdentity()
            userRepository.findByExternalId(externalId)?.id?.toString()
        }.getOrNull()
            ?.let { Optional.of(it) }
            ?: Optional.empty()
}
