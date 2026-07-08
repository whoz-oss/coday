package io.whozoss.agentos.auth

import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.sdk.auth.AuthService
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.credential.Credential
import java.util.UUID

/**
 * Request-scoped implementation of [AuthService].
 *
 * Created per tool invocation with the resolved (namespaceId, userId) from
 * [io.whozoss.agentos.sdk.tool.ToolContext]. Delegates to the singleton
 * [AuthSettingService] and [CredentialService] for actual data access.
 *
 * Not a Spring bean — instantiated by [AuthServiceFactory].
 */
class AuthServiceImpl(
    private val namespaceId: UUID,
    private val userId: UUID,
    private val authSettingService: AuthSettingService,
    private val credentialService: CredentialService,
) : AuthService {

    override fun resolveAuthSetting(name: String): AuthSetting =
        authSettingService.resolveAuthSetting(namespaceId, userId, name)

    override fun resolveCredential(authSettingId: UUID): Credential? =
        credentialService.resolve(userId, authSettingId)

    override fun storeCredential(credential: Credential): Credential =
        credentialService.store(credential)

    override fun revokeCredential(authSettingId: UUID): Boolean =
        credentialService.revoke(userId, authSettingId)
}
