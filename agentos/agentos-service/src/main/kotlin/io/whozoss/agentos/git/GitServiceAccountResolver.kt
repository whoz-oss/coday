package io.whozoss.agentos.git

import io.whozoss.agentos.authSetting.ApiKeyAuthSetting
import io.whozoss.agentos.authSetting.AuthSetting
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.authSetting.BasicAuthAuthSetting
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.git.core.GitCredentials
import mu.KLogging
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Resolves the namespace service account used for every Git operation.
 *
 * ## Why this is not the ordinary credential path
 *
 * The existing `CredentialProvider` is built per agent run and only when the run has an identified
 * user (`AgentServiceImpl` skips it otherwise), it resolves an auth setting **by name** through the
 * four-layer overlay, and a per-user credential row wins over the shared one. All three are wrong
 * here: provisioning runs in a background worker with no user at all, and the identity must not
 * change with whoever happened to open the case — a member with a homonymous personal auth setting
 * would otherwise silently become the Git identity.
 *
 * So this resolver reads the setting **by UUID**, checks it really is shared by the namespace that
 * asked, and never consults a per-user row.
 */
@Component
class GitServiceAccountResolver(
    private val authSettingService: AuthSettingService,
    @param:Lazy private val associations: GitRepositoryAssociationService? = null,
) {
    /**
     * @throws BadRequestException when the referenced auth setting is missing, out of scope, or of
     *   a kind that cannot authenticate an HTTPS Git remote.
     */
    fun resolve(settings: GitRepositorySettings): GitCredentials.UsernamePassword {
        // Existing workspaces keep their base and setup snapshot, but use the namespace's
        // current service account. Removing the association preserves the historical account.
        val association = associations?.findSettings(settings.namespaceId)
        if (association != null && association.repositoryUrl != settings.repositoryUrl) {
            throw BadRequestException("The workspace repository no longer matches the namespace association")
        }
        val authSettingId = association?.serviceAuthSettingId ?: settings.serviceAuthSettingId
        val authSetting =
            authSettingService.findById(authSettingId)
                ?: throw BadRequestException(
                    "The service account referenced by this namespace's Git association " +
                        "($authSettingId) does not exist. A deleted or recreated auth setting " +
                        "is not the same identity: re-associate the repository explicitly.",
                )

        assertSharedByNamespace(authSetting, settings)

        return when (authSetting) {
            // Forges accept "any username + token as password" over HTTPS; the conventional
            // placeholder keeps the token out of the username field of any log a forge keeps.
            is BearerTokenAuthSetting -> credentials(TOKEN_USERNAME, authSetting.token, authSetting)
            is ApiKeyAuthSetting -> credentials(TOKEN_USERNAME, authSetting.apiKey, authSetting)
            is BasicAuthAuthSetting -> credentials(authSetting.username, authSetting.password, authSetting)
            else -> throw BadRequestException(
                "Auth setting '${authSetting.name}' is of type ${authSetting.authType}, which cannot authenticate " +
                    "a Git remote from a background worker: OAuth kinds need an interactive per-user flow. " +
                    "Use a BEARER_TOKEN or BASIC_AUTH setting holding a repository-scoped token.",
            )
        }
    }

    /**
     * The setting must be the namespace's own shared one. A platform-level or user-scoped setting
     * would let an identity from outside the namespace — or a member's personal one — act as its
     * service account.
     */
    private fun assertSharedByNamespace(
        authSetting: AuthSetting,
        settings: GitRepositorySettings,
    ) {
        if (authSetting.userId != null || authSetting.namespaceId != settings.namespaceId) {
            throw BadRequestException(
                "Auth setting ${authSetting.id} is not shared by namespace ${settings.namespaceId} " +
                    "(namespaceId=${authSetting.namespaceId}, userId=${authSetting.userId}). The Git service " +
                    "account must be a namespace-shared setting of that same namespace.",
            )
        }
    }

    private fun credentials(
        username: String,
        secret: String,
        authSetting: AuthSetting,
    ): GitCredentials.UsernamePassword {
        if (secret.isBlank()) {
            throw BadRequestException("Auth setting '${authSetting.name}' holds no secret")
        }
        if (secret.any(Character::isISOControl) || username.any(Character::isISOControl)) {
            throw BadRequestException("Git service account credentials must not contain control characters")
        }
        return GitCredentials.UsernamePassword(username = username, secret = secret)
    }

    companion object : KLogging() {
        /** Conventional placeholder username for token authentication over HTTPS. */
        private const val TOKEN_USERNAME = "x-access-token"
    }
}
