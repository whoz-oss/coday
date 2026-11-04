import { inject, Injectable } from '@angular/core'
import {
  MessagingAppManifestDto,
  MessagingBotControllerService,
  MessagingBotDto,
  MessagingBotManagerDto,
  MessagingLinkDto,
  MessagingPlatform,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/**
 * MessagingBotConfigService — configuring messaging: the bots of an agent, and the bot managers of the
 * instance. Thin wrapper over MessagingBotControllerService: the pages that use it hold their own state, since
 * each shows one agent or the instance's managers. Every change applies at once on the server, with no restart.
 */
@Injectable({ providedIn: 'root' })
export class MessagingBotConfigService {
  private readonly botController = inject(MessagingBotControllerService)

  agentBots(namespaceId: string, agentId: string): Observable<MessagingBotDto[]> {
    return this.botController.listAgentMessagingBots(namespaceId, agentId)
  }

  /**
   * Gives the agent the bot of [token]: a Telegram bot created with BotFather, or a Slack app, whose [appToken]
   * receives the messages.
   */
  attach(
    namespaceId: string,
    agentId: string,
    platform: MessagingPlatform,
    token: string,
    appToken?: string
  ): Observable<MessagingBotDto> {
    return this.botController.attachMessagingBot(namespaceId, agentId, { platform, token, appToken })
  }

  /** The manifest of the app to create for the agent, on a platform where a bot is such an app (Slack). */
  manifest(namespaceId: string, agentId: string, platform: MessagingPlatform): Observable<MessagingAppManifestDto> {
    return this.botController.getMessagingBotAppManifest(namespaceId, agentId, platform)
  }

  /** The single-use link to the bot manager that creates the agent's bot, with no token to copy. */
  requestCreation(namespaceId: string, agentId: string, platform: MessagingPlatform): Observable<MessagingLinkDto> {
    return this.botController.requestMessagingBotCreation(namespaceId, agentId, { platform })
  }

  setEnabled(botId: string, enabled: boolean): Observable<MessagingBotDto> {
    return this.botController.updateMessagingBot(botId, { enabled })
  }

  remove(botId: string): Observable<void> {
    return this.botController.deleteMessagingBot(botId)
  }

  managers(): Observable<MessagingBotManagerDto[]> {
    return this.botController.listMessagingBotManagers()
  }

  setManager(platform: MessagingPlatform, token: string): Observable<MessagingBotManagerDto> {
    return this.botController.setMessagingBotManager({ platform, token })
  }

  removeManager(platform: MessagingPlatform): Observable<void> {
    return this.botController.deleteMessagingBotManager(platform)
  }
}
