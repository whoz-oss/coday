package io.whozoss.agentos.plugin.filesystem

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Result of clearing the caches of the configuration files read from the filesystem.
 *
 * @property clearedDirectories How many cached directories were forgotten: the next read of each one reads its files again.
 */
@Schema(name = "FilesystemCacheClearResult")
data class FilesystemCacheClearResult(
    val clearedDirectories: Int,
)

/**
 * Administration endpoint that makes the service read again the configuration files of every namespace
 * (agents, integrations, prompts, skills, AI models under `<configPath>/`), instead of waiting for the
 * cache to expire.
 *
 * Super-admin only: it is meant for whoever edits those files, typically on a local instance.
 */
@RestController
@RequestMapping("/api/admin/filesystem-caches", produces = [MediaType.APPLICATION_JSON_VALUE])
class FilesystemCacheController(
    private val userService: UserService,
) {
    @PostMapping("/clear")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        operationId = "clearFilesystemCaches",
        summary = "Clear the caches of the configuration files read from the filesystem",
        description =
            "Forgets what was read from the configuration directories of the namespaces (agents, integrations, " +
                "prompts, skills, AI models), so that the next read of each one reads its files again. " +
                "Only the AgentOS instance that receives the call is cleared: with several instances, the others " +
                "keep their caches until they expire.",
    )
    fun clear(): FilesystemCacheClearResult {
        val cleared = FilesystemYamlCacheRegistry.clearAll()
        logger.info {
            "[FilesystemCache] ${userService.getCurrentUser().externalId} cleared the filesystem config caches ($cleared directories)"
        }
        return FilesystemCacheClearResult(cleared)
    }

    companion object : KLogging()
}
