package io.whozoss.agentos.auth

import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.sdk.auth.AuthService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Spring-managed factory that creates request-scoped [AuthService] instances.
 *
 * Holds references to the singleton [AuthSettingService] and [CredentialService]
 * and injects them into each [AuthServiceImpl] together with the per-request
 * (namespaceId, userId) identity.
 */
@Component
class AuthServiceFactory(
    private val authSettingService: AuthSettingService,
    private val credentialService: CredentialService,
) {
    fun create(namespaceId: UUID, userId: UUID): AuthService =
        AuthServiceImpl(namespaceId, userId, authSettingService, credentialService)
}
