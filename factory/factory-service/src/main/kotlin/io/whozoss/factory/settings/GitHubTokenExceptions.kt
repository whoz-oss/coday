package io.whozoss.factory.settings

import io.whozoss.factory.error.FactoryException

/** 400 - the GitHub token is missing, blank, too long or contains whitespace or control characters. */
class InvalidGitHubTokenException(
    message: String = "token must be a non-blank string without whitespace (at most ${GitHubTokenSettingsService.MAX_TOKEN_LENGTH} characters)",
) : FactoryException(statusCode = 400, errorCode = "INVALID_VALUE", message = message)
