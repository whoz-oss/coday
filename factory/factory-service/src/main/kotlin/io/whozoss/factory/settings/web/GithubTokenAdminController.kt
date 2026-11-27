package io.whozoss.factory.settings.web

import io.swagger.v3.oas.annotations.Parameter
import io.whozoss.factory.settings.GitHubTokenSettingsService
import io.whozoss.factory.web.AuthenticatedUser
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP boundary of the GitHub token used by the deterministic PR checks step.
 *
 * Like [RunConcurrencyAdminController], any authenticated user may use it (this Factory has no admin role yet).
 * No response ever contains the token, only a status, the verified GitHub login and a masked hint. Responses use
 * the canonical `{ "data": ... }` envelope.
 */
@RestController
@RequestMapping("/api/factory/admin/settings/github-token")
class GithubTokenAdminController(
    private val service: GitHubTokenSettingsService,
) {

    /** The status of the effective token (admin, environment or `gh`), verified against GitHub (cached for 30 s). */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getGitHubToken(): Map<String, Any?> = mapOf("data" to service.status())

    /**
     * Verifies the token live, then saves it unless GitHub rejects it (answer `status: invalid`, previous token kept).
     * 400 `INVALID_VALUE` when the token is blank.
     */
    @PutMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun updateGitHubToken(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): Map<String, Any?> = mapOf("data" to service.save(body?.get("token") as? String, user.userId))

    /** Removes the token set from the Admin page; the environment / `gh` token, if any, takes over. */
    @DeleteMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun clearGitHubToken(@Parameter(hidden = true) user: AuthenticatedUser): Map<String, Any?> =
        mapOf("data" to service.clear(user.userId))

    /** Re-tests the effective token against GitHub now, bypassing the 30 s cache. */
    @PostMapping("/test", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun testGitHubToken(): Map<String, Any?> = mapOf("data" to service.status(forceVerify = true))
}
