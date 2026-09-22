package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.NamespaceService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Namespace settings: associate a repository, and choose whether new root cases get a worktree.
 *
 * A dedicated surface rather than the generic integration-configuration screens, for three
 * reasons this feature made concrete:
 *
 * - the association is a **capability of the namespace**, not a tool an agent can pick, and the
 *   generic screens list every namespace configuration as agent-selectable;
 * - the service account must be referenced by **UUID**, while the generic form's auth picker
 *   stores a *name* — and a name is resolved through the user-level overlays, so a member with a
 *   homonymous personal setting would silently become the Git identity;
 * - the screen needs the checkout's preparation state next to the settings, which the generic
 *   CRUD has no notion of.
 *
 * Underneath it is still one namespace-shared `GIT_REPOSITORY` configuration, so the existing
 * persistence, uniqueness constraint, audit and validation all apply unchanged.
 */
@RestController
class NamespaceGitController(
    private val integrationConfigService: IntegrationConfigService,
    private val associationService: GitRepositoryAssociationService,
    private val checkoutService: RepositoryCheckoutService,
    private val objectMapper: ObjectMapper,
    private val namespaceService: NamespaceService,
    private val gitAvailability: GitAvailability,
) {
    @GetMapping("/api/namespaces/{namespaceId}/git", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun getAssociation(
        @PathVariable namespaceId: UUID,
    ): NamespaceGitResource {
        requireGit()
        val settings =
            try {
                associationService.findSettings(namespaceId)
            } catch (e: Exception) {
                // A stored-but-unusable association must still be visible in the screen that can
                // repair it, so report it rather than failing the whole page.
                logger.warn { "Namespace $namespaceId has an unusable Git association (${e.javaClass.simpleName})" }
                return NamespaceGitResource(associated = true, checkoutStatus = "FAILED",
                    checkoutFailureReason = "Cannot load the saved Git association. Retry before changing its settings.")
            } ?: return NamespaceGitResource(associated = false)

        val checkout = checkoutService.findByNamespaceId(namespaceId)
        return NamespaceGitResource(
            associated = true,
            repositoryUrl = settings.repositoryUrl,
            mainBranch = settings.mainBranch,
            serviceAuthSettingId = settings.serviceAuthSettingId,
            autoWorktreeForRootCases = settings.autoWorktreeForRootCases,
            setupCommand = settings.setupCommand,
            checkoutStatus = checkout?.status?.name,
            checkoutFailureReason = checkout?.failureReason,
            lastFetchedAt = checkout?.lastFetchedAt,
        )
    }

    /**
     * Associate a repository, or update the existing association.
     *
     * Idempotent by namespace: there is at most one association, so this creates it or edits it in
     * place. Values are validated server-side before anything is stored.
     */
    @PutMapping(
        "/api/namespaces/{namespaceId}/git",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun setAssociation(
        @PathVariable namespaceId: UUID,
        @Valid @RequestBody request: NamespaceGitRequest,
    ): NamespaceGitResource {
        requireGit()
        namespaceService.findById(namespaceId)
            ?: throw ResourceNotFoundException("Namespace not found: $namespaceId")
        val existing = integrationConfigService.findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE)

        val parameters =
            objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                buildMap {
                    put(GitRepositoryIntegration.PARAM_REPOSITORY_URL, request.repositoryUrl.trim())
                    put(
                        GitRepositoryIntegration.PARAM_MAIN_BRANCH,
                        request.mainBranch?.trim()?.takeIf { it.isNotEmpty() } ?: GitRepositoryIntegration.DEFAULT_MAIN_BRANCH,
                    )
                    put(GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID, request.serviceAuthSettingId.toString())
                    put(GitRepositoryIntegration.PARAM_AUTO_WORKTREE, request.autoWorktreeForRootCases)
                    request.setupCommand?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        put(GitRepositoryIntegration.PARAM_SETUP_COMMAND, it)
                    }
                },
            )

        val config =
            existing?.copy(parameters = parameters)
                ?: IntegrationConfig(
                    namespaceId = namespaceId,
                    userId = null,
                    name = DEFAULT_CONFIG_NAME,
                    integrationType = GitRepositoryIntegration.TYPE,
                    description = "Repository associated with this namespace",
                    parameters = parameters,
                )

        when (existing) {
            null -> integrationConfigService.create(config)
            else -> integrationConfigService.update(config)
        }
        logger.info { "Namespace $namespaceId associated with ${request.repositoryUrl}" }

        return getAssociation(namespaceId)
    }

    /**
     * Remove the association.
     *
     * Only the configuration is removed. Existing workspaces keep their binding and their
     * worktree: a family is equipped by the presence of its binding, never by the namespace's
     * current settings, so disassociating must not strip work in progress.
     */
    @DeleteMapping("/api/namespaces/{namespaceId}/git", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun removeAssociation(
        @PathVariable namespaceId: UUID,
    ): NamespaceGitResource {
        requireGit()
        val existing =
            integrationConfigService.findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE)
                ?: throw ResourceNotFoundException("Namespace $namespaceId has no repository associated")

        integrationConfigService.delete(existing.id)
        logger.info { "Namespace $namespaceId disassociated from its repository" }
        return NamespaceGitResource(associated = false)
    }

    /** These settings exist only while the GIT plugin is loaded, see [GitAvailability]. */
    private fun requireGit() {
        if (!gitAvailability.isAvailable()) {
            throw ResourceNotFoundException("Git is not available on this instance: the GIT plugin is not loaded")
        }
    }

    companion object : KLogging() {
        private const val DEFAULT_CONFIG_NAME = "project-repository"
    }
}
