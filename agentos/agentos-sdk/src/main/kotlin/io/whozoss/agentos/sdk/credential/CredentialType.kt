package io.whozoss.agentos.sdk.credential

enum class CredentialType {
    OAUTH_TOKENS,   // accessToken, refreshToken, expiresAt, tokenType, scope
    API_KEY,        // key
    BEARER_TOKEN,   // token
    BASIC_AUTH,     // username, password
}
