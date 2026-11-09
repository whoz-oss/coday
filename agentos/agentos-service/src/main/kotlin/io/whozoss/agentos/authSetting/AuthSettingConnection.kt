package io.whozoss.agentos.authSetting

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/** The caller's own connection to an OAuth AuthSetting. Never carries a token. */
@Schema(name = "AuthSettingConnection")
data class AuthSettingConnection(
    @field:Schema(
        description =
            "True when the caller holds a stored connection that can still serve a run (valid access token, " +
                "or expired but refreshable).",
    )
    val connected: Boolean,
    @field:Schema(
        description =
            "When the stored connection was last written, null when not connected. A new authorization " +
                "always moves it forward, which is how a client sees a reconnection complete.",
        nullable = true,
    )
    val connectedAt: Instant? = null,
)

/** Where the user must go to authorize. */
@Schema(name = "AuthSettingConnectResponse")
data class AuthSettingConnectResponse(
    @field:Schema(description = "Authorization URL to open in a popup; the flow waits for its callback.")
    val authorizationUrl: String,
)
