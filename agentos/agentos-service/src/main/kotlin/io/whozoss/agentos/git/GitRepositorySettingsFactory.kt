package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.git.core.GitRefNames
import io.whozoss.agentos.git.core.GitRemoteUrlValidator
import io.whozoss.agentos.git.core.InvalidGitRemoteException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Turns a `GIT_REPOSITORY` configuration row into a validated [GitRepositorySettings].
 *
 * Saving validates the remote URL as well as the stored shape. Reading a saved association may
 * skip DNS and transport validation: the runner repeats those checks before actual network I/O.
 */
@Component
class GitRepositorySettingsFactory(
    private val urlValidator: GitRemoteUrlValidator,
) {
    /**
     * @throws BadRequestException when the row is not a usable namespace Git association.
     */
    fun fromConfig(config: IntegrationConfig, validateRemote: Boolean = true): GitRepositorySettings {
        val namespaceId =
            config.namespaceId
                ?: throw BadRequestException("A ${GitRepositoryIntegration.TYPE} configuration must be namespace-scoped")
        if (config.userId != null) {
            throw BadRequestException(
                "A ${GitRepositoryIntegration.TYPE} configuration must be namespace-shared, not scoped to a user",
            )
        }

        val parameters =
            config.parameters
                ?: throw BadRequestException("A ${GitRepositoryIntegration.TYPE} configuration requires parameters")

        val repositoryUrl =
            parameters.textOrNull(GitRepositoryIntegration.PARAM_REPOSITORY_URL)
                ?: throw BadRequestException("'${GitRepositoryIntegration.PARAM_REPOSITORY_URL}' is required")
        if (validateRemote) {
            try {
                urlValidator.validate(repositoryUrl)
            } catch (e: InvalidGitRemoteException) {
                throw BadRequestException(e.message ?: "The repository URL is not allowed")
            }
        }

        val mainBranch =
            parameters.textOrNull(GitRepositoryIntegration.PARAM_MAIN_BRANCH)
                ?: GitRepositoryIntegration.DEFAULT_MAIN_BRANCH
        if (!GitRefNames.isValidBranchName(mainBranch)) {
            throw BadRequestException("'$mainBranch' is not a valid branch name")
        }

        val serviceAuthSettingIdRaw =
            parameters.textOrNull(GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID)
                ?: throw BadRequestException(
                    "'${GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID}' is required: Git operations run " +
                        "under a namespace service account, never under the identity of whoever opened the case",
                )
        val serviceAuthSettingId =
            try {
                UUID.fromString(serviceAuthSettingIdRaw)
            } catch (e: IllegalArgumentException) {
                throw BadRequestException(
                    "'${GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID}' must be the UUID of an auth setting, " +
                        "not its name — a name is resolved through user-level overlays and would let a member " +
                        "substitute their own credential (${e.message})",
                )
            }

        return GitRepositorySettings(
            configId = config.id,
            namespaceId = namespaceId,
            repositoryUrl = repositoryUrl,
            mainBranch = mainBranch,
            serviceAuthSettingId = serviceAuthSettingId,
        )
    }

    private fun JsonNode.textOrNull(field: String): String? =
        get(field)
            ?.takeIf { !it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

}
