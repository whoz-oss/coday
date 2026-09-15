package io.whozoss.agentos.auth

import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.credential.CredentialService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Spring-managed factory that creates per-identity [AuthService] instances by plain construction
 * (no request scope: the instances do not depend on the HTTP request and may run on any thread).
 *
 * Holds references to the singleton [AuthSettingService] and [CredentialService]
 * and injects them into each [AuthServiceImpl] together with the caller's
 * (namespaceId, userId) identity.
 */
@Component
class AuthServiceFactory(
    private val authSettingService: AuthSettingService,
    private val credentialService: CredentialService,
) {
    fun create(
        namespaceId: UUID,
        userId: UUID,
    ): AuthService =
        AuthServiceImpl(
            namespaceId = namespaceId,
            userId = userId,
            authSettingService = authSettingService,
            credentialService = credentialService,
        )
}
