package io.whozoss.agentos.plugins.http.net

import java.net.URI

/** Outcome of [OutboundUrlPolicy.validate]. */
sealed interface UrlCheck {
    /** The URL is acceptable for an outbound call. */
    data class Ok(val uri: URI) : UrlCheck

    /** The URL is rejected; [reason] is safe to surface to an administrator. */
    data class Rejected(val reason: String) : UrlCheck
}
