import { DatePipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { MessagingBotDto, MessagingIdentityDto, MessagingLinkDto } from '@whoz-oss/agentos-api-client'
import { fromEvent } from 'rxjs'
import { MessagingStateService } from '../../services/messaging-state.service'
import { messagingPlatformName } from './messaging-platforms'

/**
 * MessagingAccountsComponent — the "Messaging" section of the profile: the bots of the agents the user can talk
 * to, connecting an account to one, the linked accounts, unlinking them.
 *
 * Connecting asks the backend for a single-use link, shown as a button that opens the bot: the account that
 * presses Start in the application is the one linked. The lists are read again when the window gets the focus
 * back, which is when the user returns from the application. Hidden when no agent has a bot for the user and no
 * account is linked.
 */
@Component({
  selector: 'agentos-messaging-accounts',
  imports: [DatePipe],
  templateUrl: './messaging-accounts.component.html',
  styleUrl: './messaging-accounts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessagingAccountsComponent {
  private readonly messagingState = inject(MessagingStateService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly bots = this.messagingState.bots
  protected readonly platformName = messagingPlatformName

  /** Linked accounts whose bot was removed since: they can only be unlinked. */
  protected readonly orphanIdentities = computed(() =>
    this.messagingState.identities().filter((identity) => !this.bots().some((bot) => bot.id === identity.botId))
  )

  protected readonly visible = computed(() => this.bots().length > 0 || this.messagingState.identities().length > 0)

  /** The link issued for each bot, until the account it connects shows up. */
  protected readonly links = signal<Record<string, MessagingLinkDto>>({})
  protected readonly pendingUnlinkId = signal<string | null>(null)
  protected readonly error = signal<string | null>(null)

  constructor() {
    this.refresh()
    fromEvent(window, 'focus')
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (Object.keys(this.links()).length > 0) this.refresh()
      })
  }

  protected identityOf(bot: MessagingBotDto): MessagingIdentityDto | undefined {
    return this.messagingState.identities().find((identity) => identity.botId === bot.id)
  }

  protected linkOf(bot: MessagingBotDto): MessagingLinkDto | undefined {
    return this.links()[bot.id]
  }

  protected connect(bot: MessagingBotDto): void {
    this.error.set(null)
    this.messagingState
      .createLink(bot.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (link) => this.links.update((links) => ({ ...links, [bot.id]: link })),
        error: (err) => {
          console.error(`[MessagingAccounts] Could not get a link to @${bot.username}:`, err)
          this.error.set(`Could not get a link to @${bot.username}. Please try again.`)
        },
      })
  }

  protected copy(text: string): void {
    navigator.clipboard?.writeText(text).catch(() => undefined)
  }

  protected unlink(identity: MessagingIdentityDto): void {
    this.pendingUnlinkId.set(null)
    this.messagingState
      .unlink(identity.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err) => {
          console.error(`[MessagingAccounts] Unlink failed for ${identity.id}:`, err)
          this.error.set('Could not unlink the account. Please try again.')
        },
      })
  }

  private refresh(): void {
    this.messagingState
      .load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ([, identities]) =>
          this.links.update((links) =>
            Object.fromEntries(
              Object.entries(links).filter(([botId]) => !identities.some((identity) => identity.botId === botId))
            )
          ),
        error: (err) => console.error('[MessagingAccounts] Could not load the messaging accounts:', err),
      })
  }
}
