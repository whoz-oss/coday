package io.whozoss.agentos.messaging

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.api.messaging.MessagingApi
import io.whozoss.agentos.sdk.api.messaging.MessagingBotDto
import io.whozoss.agentos.sdk.api.messaging.MessagingCapabilitiesDto
import io.whozoss.agentos.sdk.api.messaging.MessagingContinueRequest
import io.whozoss.agentos.sdk.api.messaging.MessagingIdentityDto
import io.whozoss.agentos.sdk.api.messaging.MessagingLinkDto
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for messaging as a user. Implements [MessagingApi] so external consumers can declare a Feign client
 * against the SDK interface.
 *
 * Every endpoint works on the caller's own accounts, and on the bots of the agents the caller may use: a bot of
 * another agent is not found.
 */
@RestController
@RequestMapping("/api", produces = [MediaType.APPLICATION_JSON_VALUE])
class MessagingController(
    private val gateway: MessagingGateway,
    private val identities: MessagingIdentityService,
    private val bots: MessagingBotService,
    private val registry: MessagingBotRegistry,
    private val views: MessagingBotViews,
    private val agentConfigService: AgentConfigService,
    private val users: UserService,
) : MessagingApi {
    @GetMapping("/messaging/capabilities")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "What the instance offers for messaging, such as creating the bot of an agent with no token")
    override fun getMessagingCapabilities(): MessagingCapabilitiesDto =
        MessagingCapabilitiesDto(botCreation = MessagingPlatform.entries.filter { registry.manager(it) != null })

    @GetMapping("/messaging/bots")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List the bots of the agents the current user can talk to")
    override fun listMessagingBots(): List<MessagingBotDto> {
        val userId = users.getCurrentUser().id
        return bots
            .findAllAgentBots()
            .filter { it.enabled }
            .mapNotNull { bot -> bots.usableAgent(userId, bot.ref())?.let { agent -> views.toDto(bot, userId, agent.name) } }
    }

    @PostMapping("/messaging/bots/{botId}/link")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a single-use link that connects a messaging account to the current user through a bot")
    override fun createMessagingLink(
        @PathVariable botId: UUID,
    ): MessagingLinkDto {
        val userId = users.getCurrentUser().id
        val bot =
            bots.findById(botId)?.takeIf { it.role == MessagingBotRole.AGENT && bots.usableAgent(userId, it.ref()) != null }
                ?: throw ResourceNotFoundException("Messaging bot not found: $botId")
        val adapter = registry.adapter(bot.id) ?: throw ConflictException("@${bot.username} is not running")
        val issued = identities.issueLinkToken(userId, adapter.bot)
        return MessagingLinkDto(url = adapter.linkUrl(issued.token), code = adapter.linkCode(issued.token), expiresAt = issued.expiresAt)
    }

    @GetMapping("/messaging/identities")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List the messaging accounts linked to the current user")
    override fun listMessagingIdentities(): List<MessagingIdentityDto> =
        identities.findByUser(users.getCurrentUser().id).map { identity ->
            val bot = bots.findByAccount(identity.platform, identity.accountId)
            MessagingIdentityDto(
                id = identity.id,
                platform = identity.platform,
                botId = bot?.id,
                botUsername = bot?.username,
                agentName = bot?.agentId?.let { agentConfigService.findById(it)?.name },
                displayName = identity.displayName,
                linkedAt = identity.metadata.created,
            )
        }

    @DeleteMapping("/messaging/identities/{identityId}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unlink a messaging account of the current user")
    override fun deleteMessagingIdentity(
        @PathVariable identityId: UUID,
    ) {
        if (!identities.unlink(users.getCurrentUser().id, identityId)) {
            throw ResourceNotFoundException("Messaging account not found: $identityId")
        }
    }

    @PostMapping("/cases/{caseId}/messaging/continue", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Continue the case in the direct chat of the current user with a bot")
    override fun continueCaseInMessaging(
        @PathVariable caseId: UUID,
        @RequestBody request: MessagingContinueRequest,
    ) {
        val user = users.getCurrentUser()
        bots.findById(request.botId)?.takeIf { it.role == MessagingBotRole.AGENT && bots.usableAgent(user.id, it.ref()) != null }
            ?: throw ResourceNotFoundException("Messaging bot not found: ${request.botId}")
        gateway.continueCaseIn(botId = request.botId, caseId = caseId, user = user)
    }
}
