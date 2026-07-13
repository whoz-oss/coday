package io.whozoss.agentos.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Receives the OAuth 2.1 authorization code callback posted by the browser popup
 * after the user completes the consent flow.
 *
 * The browser popup is opened by the frontend with the authorization URL constructed
 * by [OAuthFlowService]. After the provider redirects the browser to the AgentOS
 * callback page, the frontend JavaScript posts `{ code, state }` here to hand the
 * authorization code back to the waiting server-side flow.
 *
 * Authentication: the request arrives in the user's authenticated session — the same
 * auth proxy headers that protect every other API endpoint are present. Only
 * authenticated users may post callbacks.
 */
@RestController
class OAuthCallbackController(
    private val pendingRegistry: OAuthPendingRegistry,
) {

    /**
     * POST /api/oauth/callback
     *
     * Resolves the pending OAuth flow identified by [OAuthCallbackRequest.state] with
     * the received [OAuthCallbackRequest.code].
     *
     * Returns 200 OK when the flow was found and resolved, or 400 Bad Request when
     * [state] is unknown (no pending flow). The 400 response prevents an attacker from
     * fishing for valid state values.
     */
    @Operation(
        summary = "Receive OAuth authorization code callback",
        description =
            "Called by the frontend popup after the OAuth provider redirects back with the " +
                "authorization code. Resolves the pending server-side flow identified by `state`. " +
                "Returns 200 when resolved, 400 when the state is unknown.",
        responses = [
            ApiResponse(responseCode = "200", description = "Flow resolved successfully"),
            ApiResponse(responseCode = "400", description = "Unknown or already-consumed state"),
        ],
    )
    @PostMapping("/api/oauth/callback", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    fun handleCallback(
        @RequestBody request: OAuthCallbackRequest,
    ): ResponseEntity<Void> {
        logger.debug { "OAuth callback received for state=${request.state}" }
        val resolved = pendingRegistry.resolve(request.state, request.code)
        return when {
            resolved -> {
                logger.info { "OAuth callback successfully resolved for state=${request.state}" }
                ResponseEntity.ok().build()
            }
            else -> {
                logger.warn { "OAuth callback received for unknown state=${request.state}" }
                ResponseEntity.badRequest().build()
            }
        }
    }

    companion object : KLogging()
}

/**
 * Request body for the OAuth callback endpoint.
 *
 * Both fields are mandatory: the authorization code issued by the provider and the
 * opaque state value that ties this callback to a specific pending flow.
 */
data class OAuthCallbackRequest(
    val code: String,
    val state: String,
)
