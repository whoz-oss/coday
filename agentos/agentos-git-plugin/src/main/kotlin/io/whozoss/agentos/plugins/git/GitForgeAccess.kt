package io.whozoss.agentos.plugins.git

import io.whozoss.agentos.git.core.GitCredentials
import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.git.core.GitHubRepository
import io.whozoss.agentos.sdk.auth.CredentialProvider
import io.whozoss.agentos.sdk.credential.CredentialType

/**
 * The identity of the user running the case, as the GIT integration's auth setting provides it.
 *
 * Push and pull requests use this user's own credentials; there is no fallback to another account.
 * Commits are authored as the same person.
 */
internal class GitForgeAccess(
    private val credentialProvider: CredentialProvider?,
    private val userExternalId: String?,
    private val gitHub: GitHubApi,
) {
    data class Token(val git: GitCredentials.UsernamePassword, val api: String)

    data class Identity(val name: String, val email: String)

    fun token(): Token {
        val credential = credentialProvider?.invoke()
            ?: throw GitToolException(
                "No Git credentials are available for you. Bind an auth setting to this GIT integration and " +
                    "authenticate with your own forge account.",
            )
        val data = credential.data
        val (username, secret) = when (credential.credentialType) {
            CredentialType.OAUTH_TOKENS -> TOKEN_USERNAME to data["accessToken"]
            CredentialType.BEARER_TOKEN -> TOKEN_USERNAME to data["token"]
            CredentialType.API_KEY -> TOKEN_USERNAME to data["key"]
            CredentialType.BASIC_AUTH -> (data["username"] ?: TOKEN_USERNAME) to data["password"]
        }
        if (secret.isNullOrBlank() || (secret + username).any(Character::isISOControl)) {
            throw GitToolException("Your Git credentials cannot be used for Git over HTTPS")
        }
        return Token(GitCredentials.UsernamePassword(username, secret), secret)
    }

    /**
     * The commit author. On GitHub it is the account behind the token, with its no-reply address so
     * the commits are attributed to it; elsewhere, the user's identity-provider email.
     */
    fun identity(repositoryUrl: String): Identity {
        if (GitHubRepository.fromRemoteUrl(repositoryUrl) != null) {
            val response = gitHub.get("user", token().api)
            val user = response.body
            if (response.status != 200 || user == null) {
                throw GitToolException("GitHub did not return your account (HTTP ${response.status})")
            }
            val login = user.path("login").asText()
            val id = user.path("id").asLong()
            if (login.isBlank() || id <= 0) throw GitToolException("GitHub did not return your account")
            val name = user.path("name").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } ?: login
            return Identity(name, "$id+$login@users.noreply.github.com")
        }
        val email = userExternalId?.takeIf { '@' in it && it.none(Character::isISOControl) }
            ?: throw GitToolException("Cannot determine the commit author: your account has no email address")
        return Identity(email, email)
    }

    private companion object {
        /** Forges accept any username with a token as password; this one keeps the token out of logs. */
        const val TOKEN_USERNAME = "x-access-token"
    }
}

/** A refusal or failure whose message is safe to return to the agent. */
internal class GitToolException(message: String) : RuntimeException(message)
