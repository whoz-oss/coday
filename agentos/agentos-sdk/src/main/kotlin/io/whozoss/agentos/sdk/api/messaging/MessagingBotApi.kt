package io.whozoss.agentos.sdk.api.messaging

import java.util.UUID

/**
 * HTTP API contract for configuring messaging: the bots of agents, and the bot manager of the instance that
 * creates them with no token to copy. Bots are taken into account at once, with no restart.
 *
 * Implemented by `MessagingBotController` in agentos-service. External consumers implement this interface as a
 * Feign client, adding their own `@FeignClient` and routing annotations.
 *
 * Authorization summary (enforced server-side, not expressed here):
 * - [listAgentMessagingBots], [getMessagingBotAppManifest]: READ on the namespace
 * - [attachMessagingBot], [requestMessagingBotCreation], [updateMessagingBot], [deleteMessagingBot]: WRITE on
 *   the namespace of the bot (namespace ADMIN)
 * - [listMessagingBotManagers], [setMessagingBotManager], [deleteMessagingBotManager]: super-admin
 */
interface MessagingBotApi {
    /** GET /api/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots — the bots of an agent. */
    fun listAgentMessagingBots(
        namespaceId: UUID,
        agentId: UUID,
    ): List<MessagingBotDto>

    /**
     * POST /api/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots — gives the agent a bot created
     * outside AgentOS, from its token, replacing the agent's bot on that platform. 400 when the platform refuses
     * the token, 404 when the agent does not answer in the namespace, 409 when the bot already serves another
     * agent.
     */
    fun attachMessagingBot(
        namespaceId: UUID,
        agentId: UUID,
        request: MessagingBotAttachRequest,
    ): MessagingBotDto

    /**
     * GET /api/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots/manifest?platform= — the manifest
     * of the app to create for the agent, on a platform where a bot is an app the admin creates (Slack). 409 on a
     * platform without app manifests.
     */
    fun getMessagingBotAppManifest(
        namespaceId: UUID,
        agentId: UUID,
        platform: MessagingPlatform,
    ): MessagingAppManifestDto

    /**
     * POST /api/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots/creation — a single-use link to
     * the bot manager: following it in the messaging application creates the agent's bot there, and links the
     * creator's account to it. 409 when the instance has no running bot manager on the platform.
     */
    fun requestMessagingBotCreation(
        namespaceId: UUID,
        agentId: UUID,
        request: MessagingBotCreationRequest,
    ): MessagingLinkDto

    /** PATCH /api/messaging/bots/{botId} — turns a bot on or off. */
    fun updateMessagingBot(
        botId: UUID,
        request: MessagingBotUpdateRequest,
    ): MessagingBotDto

    /** DELETE /api/messaging/bots/{botId} — removes a bot from its agent, with its token and its users' links. */
    fun deleteMessagingBot(botId: UUID)

    /** GET /api/messaging/bot-managers — the bot managers of the instance. */
    fun listMessagingBotManagers(): List<MessagingBotManagerDto>

    /**
     * PUT /api/messaging/bot-managers — sets the bot manager of a platform from its token, replacing the previous
     * one. 400 when the platform refuses the token or the bot cannot manage other bots.
     */
    fun setMessagingBotManager(request: MessagingBotManagerRequest): MessagingBotManagerDto

    /** DELETE /api/messaging/bot-managers/{platform} — removes the bot manager of a platform. The bots it created stay. */
    fun deleteMessagingBotManager(platform: MessagingPlatform)
}
