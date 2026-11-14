import { inject, Injectable, signal } from '@angular/core'
import {
  MessagingBotDto,
  MessagingCapabilitiesDto,
  MessagingControllerService,
  MessagingIdentityDto,
  MessagingLinkDto,
} from '@whoz-oss/agentos-api-client'
import { forkJoin, map, Observable, of, tap } from 'rxjs'

/**
 * MessagingStateService — messaging as the current user sees it: the bots of the agents they can talk to, and
 * the accounts they linked through them.
 *
 * Two-layer pattern: owns the state, calls MessagingControllerService. `load()` refreshes both lists, as the
 * profile does on display; `ensureLoaded()` loads them once, as the header of each case needs them.
 */
@Injectable({ providedIn: 'root' })
export class MessagingStateService {
  private readonly messagingController = inject(MessagingControllerService)

  /** The running bots of the agents the current user can use. */
  readonly bots = signal<MessagingBotDto[]>([])

  /** The accounts linked to the current user. */
  readonly identities = signal<MessagingIdentityDto[]>([])

  private loaded = false

  load(): Observable<[MessagingBotDto[], MessagingIdentityDto[]]> {
    return forkJoin([
      this.messagingController.listMessagingBots(),
      this.messagingController.listMessagingIdentities(),
    ]).pipe(
      tap(([bots, identities]) => {
        this.bots.set(bots)
        this.identities.set(identities)
        this.loaded = true
      })
    )
  }

  /** Loads the lists unless they already are. */
  ensureLoaded(): Observable<void> {
    return this.loaded ? of(undefined) : this.load().pipe(map(() => undefined))
  }

  /** What the instance offers, such as creating the bot of an agent with no token. */
  capabilities(): Observable<MessagingCapabilitiesDto> {
    return this.messagingController.getMessagingCapabilities()
  }

  /** The single-use link that connects an account to the current user through the bot [botId]. */
  createLink(botId: string): Observable<MessagingLinkDto> {
    return this.messagingController.createMessagingLink(botId)
  }

  unlink(identityId: string): Observable<void> {
    return this.messagingController.deleteMessagingIdentity(identityId).pipe(
      tap(() => {
        const removed = this.identities().find((identity) => identity.id === identityId)
        this.identities.update((list) => list.filter((identity) => identity.id !== identityId))
        if (removed?.botId) {
          this.bots.update((bots) => bots.map((bot) => (bot.id === removed.botId ? { ...bot, linked: false } : bot)))
        }
      })
    )
  }

  /** Moves the user's direct chat with the bot [botId] to the case, which posts its last answer there. */
  continueCase(caseId: string, botId: string): Observable<void> {
    return this.messagingController.continueCaseInMessaging(caseId, { botId })
  }
}
