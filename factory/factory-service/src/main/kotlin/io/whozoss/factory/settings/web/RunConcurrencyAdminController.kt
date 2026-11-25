package io.whozoss.factory.settings.web

import io.swagger.v3.oas.annotations.Parameter
import io.whozoss.factory.settings.InvalidRunConcurrencyException
import io.whozoss.factory.settings.RunConcurrencySettingsService
import io.whozoss.factory.web.AuthenticatedUser
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP boundary of the maximum number of parallel workflow runs.
 *
 * Like the artifact admin commands, any authenticated user may read and change it. The value is
 * global to the Factory instance. Responses use the canonical `{ "data": ... }` envelope and errors
 * flow through the shared error envelope.
 */
@RestController
@RequestMapping("/api/factory/admin/settings/run-concurrency")
class RunConcurrencyAdminController(
    private val settingsService: RunConcurrencySettingsService,
) {

    /** Reads the effective limit, its default and its allowed range. */
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getRunConcurrency(): Map<String, Any?> = mapOf("data" to settingsService.settings())

    /** Changes the limit at runtime; it applies at the next drain tick, with no restart. */
    @PutMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun updateRunConcurrency(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): Map<String, Any?> {
        val value = when (val raw = body?.get("maxParallelRuns")) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        } ?: throw InvalidRunConcurrencyException()
        return mapOf("data" to settingsService.update(value, user.userId))
    }
}
