package io.whozoss.agentos.messaging

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.messaging.MessagingAppManifestDto
import io.whozoss.agentos.sdk.api.messaging.MessagingBotApi
import io.whozoss.agentos.sdk.api.messaging.MessagingBotAttachRequest
import io.whozoss.agentos.sdk.api.messaging.MessagingBotCreationRequest
import io.whozoss.agentos.sdk.api.messaging.MessagingBotDto
import io.whozoss.agentos.sdk.api.messaging.MessagingBotUpdateRequest
import io.whozoss.agentos.sdk.api.messaging.MessagingLinkDto
import io.whozoss.agentos.sdk.api.messaging.MessagingBotManagerDto
import io.whozoss.agentos.sdk.api.messaging.MessagingBotManagerRequest
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for configuring messaging: the bots of agents, and the bot managers of the instance. Implements
 * [MessagingBotApi] so external consumers can declare a Feign client against the SDK interface.
 *
 * A bot is configured like its agent: by an admin of the namespace it answers in (namespace WRITE). The bot
 * managers serve the whole instance: super-admin only. Every change applies at once, with no restart.
 */
@RestController
@RequestMapping("/api", produces = [MediaType.APPLICATION_JSON_VALUE])
class MessagingBotController(
    private val bots: MessagingBotService,
    private val managedBots: MessagingManagedBots,
    private val drivers: MessagingDrivers,
    private val views: MessagingBotViews,
    private val permissionService: PermissionService,
    private val users: UserService,
) : MessagingBotApi {
    @GetMapping("/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @Operation(summary = "List the bots of an agent in a namespace")
    override fun listAgentMessagingBots(
        @PathVariable namespaceId: UUID,
        @PathVariable agentId: UUID,
    ): List<MessagingBotDto> {
        bots.requireAgent(namespaceId, agentId)
        val userId = users.getCurrentUser().id
        return bots.findAgentBots(namespaceId, agentId).map { views.toDto(it, userId) }
    }

    @PostMapping(
        "/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Give an agent a bot created outside AgentOS, from its token")
    override fun attachMessagingBot(
        @PathVariable namespaceId: UUID,
        @PathVariable agentId: UUID,
        @Valid @RequestBody request: MessagingBotAttachRequest,
    ): MessagingBotDto =
        views.toDto(
            bots.attach(
                namespaceId = namespaceId,
                agentId = agentId,
                platform = request.platform,
                token = request.token,
                appToken = request.appToken,
            ),
            users.getCurrentUser().id,
        )

    @GetMapping("/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots/manifest")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @Operation(summary = "The manifest of the app to create for an agent, on a platform where a bot is such an app")
    override fun getMessagingBotAppManifest(
        @PathVariable namespaceId: UUID,
        @PathVariable agentId: UUID,
        @RequestParam platform: MessagingPlatform,
    ): MessagingAppManifestDto {
        val agent = bots.requireAgent(namespaceId, agentId)
        val manifest =
            drivers.get(platform).appManifest(agent.name, agent.description)
                ?: throw ConflictException("A $platform bot is not created from an app manifest")
        return MessagingAppManifestDto(platform = platform, manifest = manifest.manifest, createUrl = manifest.createUrl)
    }

    @PostMapping(
        "/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots/creation",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    @Operation(summary = "Get the single-use link that creates the bot of an agent through the instance's bot manager")
    override fun requestMessagingBotCreation(
        @PathVariable namespaceId: UUID,
        @PathVariable agentId: UUID,
        @RequestBody request: MessagingBotCreationRequest,
    ): MessagingLinkDto =
        managedBots
            .requestCreation(users.getCurrentUser().id, namespaceId, agentId, request.platform)
            .let { MessagingLinkDto(url = it.url, expiresAt = it.expiresAt) }

    @PatchMapping("/messaging/bots/{botId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Turn the bot of an agent on or off")
    override fun updateMessagingBot(
        @PathVariable botId: UUID,
        @RequestBody request: MessagingBotUpdateRequest,
    ): MessagingBotDto = views.toDto(bots.setEnabled(writableBot(botId), request.enabled), users.getCurrentUser().id)

    @DeleteMapping("/messaging/bots/{botId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the bot of an agent, with its token and its users' links")
    override fun deleteMessagingBot(
        @PathVariable botId: UUID,
    ) {
        bots.remove(writableBot(botId))
    }

    @GetMapping("/messaging/bot-managers")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List the bot managers of the instance")
    override fun listMessagingBotManagers(): List<MessagingBotManagerDto> = bots.findManagers().map { views.toManagerDto(it) }

    @PutMapping("/messaging/bot-managers", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Set the bot manager of a platform from its token, replacing the previous one")
    override fun setMessagingBotManager(
        @Valid @RequestBody request: MessagingBotManagerRequest,
    ): MessagingBotManagerDto = views.toManagerDto(bots.setManager(request.platform, request.token))

    @DeleteMapping("/messaging/bot-managers/{platform}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the bot manager of a platform; the bots it created stay")
    override fun deleteMessagingBotManager(
        @PathVariable platform: MessagingPlatform,
    ) {
        if (!bots.removeManager(platform)) throw ResourceNotFoundException("No $platform bot manager")
    }

    /** The bot of an agent [botId], for a caller who administers its namespace: not found for one who cannot read it. */
    private fun writableBot(botId: UUID): MessagingBot {
        val userId = users.getCurrentUser().id.toString()
        val bot =
            bots.findById(botId)?.takeIf { it.role == MessagingBotRole.AGENT }
                ?: throw ResourceNotFoundException("Messaging bot not found: $botId")
        val namespaceId = bot.namespaceId.toString()
        return when {
            permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId, Action.WRITE) -> bot
            permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId, Action.READ) ->
                throw AccessDeniedException("Only an admin of the namespace can change its bots")
            else -> throw ResourceNotFoundException("Messaging bot not found: $botId")
        }
    }
}
