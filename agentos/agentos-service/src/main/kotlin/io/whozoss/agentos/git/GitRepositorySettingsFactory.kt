package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Turns a `GIT_REPOSITORY` configuration row into a validated [GitRepositorySettings].
 *
 * Every rule is enforced here, server-side. The JSON-schema form in the UI generates the fields
 * but performs no business validation, so this is the only place a bad association is stopped.
 */
@Component
class GitRepositorySettingsFactory(
    private val urlValidator: GitRemoteUrlValidator,
) {
    /**
     * @throws BadRequestException when the row is not a usable namespace Git association.
     */
    fun fromConfig(config: IntegrationConfig): GitRepositorySettings {
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
        urlValidator.validate(repositoryUrl)

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

        val setupCommand = parameters.textOrNull(GitRepositoryIntegration.PARAM_SETUP_COMMAND)
        if (setupCommand != null && setupCommand.length > MAX_SETUP_COMMAND_LENGTH) {
            throw BadRequestException("'${GitRepositoryIntegration.PARAM_SETUP_COMMAND}' exceeds $MAX_SETUP_COMMAND_LENGTH characters")
        }

        return GitRepositorySettings(
            configId = config.id,
            namespaceId = namespaceId,
            repositoryUrl = repositoryUrl,
            mainBranch = mainBranch,
            serviceAuthSettingId = serviceAuthSettingId,
            autoWorktreeForRootCases =
                parameters
                    .get(GitRepositoryIntegration.PARAM_AUTO_WORKTREE)
                    ?.takeIf { !it.isNull }
                    ?.asBoolean(false)
                    ?: false,
            setupCommand = setupCommand,
        )
    }

    private fun JsonNode.textOrNull(field: String): String? =
        get(field)
            ?.takeIf { !it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        /** Keeps an accidental paste (or a whole script) out of a configuration field. */
        const val MAX_SETUP_COMMAND_LENGTH: Int = 4_000
    }
}
