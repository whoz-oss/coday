package io.whozoss.agentos.sdk.authSetting

enum class AuthType {
    OAUTH_DISCOVERABLE,       // OAuth with well-known discovery URL
    OAUTH_REGISTERED,         // OAuth with pre-registered client credentials
    OAUTH_CUSTOM,             // OAuth with fully custom endpoints
    API_KEY,                  // Simple API key authentication
    BEARER_TOKEN,             // Bearer token authentication
    BASIC_AUTH,               // Username/password basic auth
    OAUTH_MCP_DISCOVERABLE,   // OAuth with MCP protocol discovery (RFC 9728 + RFC 8414)
}
