import { DatePipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { MessagingBotDto, MessagingBotStatus, MessagingLinkDto, MessagingPlatform } from '@whoz-oss/agentos-api-client'
import { fromEvent, interval, Observable } from 'rxjs'
import { MessagingBotConfigService } from '../../services/messaging-bot-config.service'
import { MessagingStateService } from '../../services/messaging-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { messagingPlatformName } from '../messaging-accounts/messaging-platforms'

/**
 * AgentMessagingComponent — the "Messaging" section of an agent's page: the agent's Telegram bot, which its
 * users talk to it through.
 *
 * An admin of the namespace gives the agent a bot in one of two ways, both taken into account at once by the
 * server, with no restart:
 * - Create a bot: a single-use link to the instance's bot manager, which creates the bot in Telegram once the
 *   admin confirms there, and links the admin's account to it. Offered when the instance has a bot manager.
 * - Use an existing bot: the token of a bot created with Telegram's BotFather.
 *
 * While a creation is pending, the section reads the bots again every few seconds and when the window gets the
 * focus back: the admin confirms in Telegram, often on their phone. Every user may connect their own account
 * to the bot from here or from their profile.
 */
@Component({
  selector: 'agentos-agent-messaging',
  imports: [DatePipe],
  templateUrl: './agent-messaging.component.html',
  styleUrl: './agent-messaging.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentMessagingComponent implements OnInit {
  private readonly botConfig = inject(MessagingBotConfigService)
  private readonly messagingState = inject(MessagingStateService)
  private readonly namespaceRole = inject(NamespaceRoleStateService)
  private readonly destroyRef = inject(DestroyRef)

  readonly namespaceId = input.required<string>()
  readonly agentId = input.required<string>()

  protected readonly platform = MessagingPlatform.TELEGRAM
  protected readonly platformName = messagingPlatformName(MessagingPlatform.TELEGRAM)

  protected readonly bots = signal<MessagingBotDto[]>([])
  protected readonly canAdminister = signal(false)
  protected readonly canCreate = signal(false)

  /** The bot of the agent on Telegram, the only platform so far. */
  protected readonly bot = computed(() => this.bots().find((bot) => bot.platform === this.platform) ?? null)

  protected readonly creationLink = signal<MessagingLinkDto | null>(null)
  protected readonly connectLink = signal<MessagingLinkDto | null>(null)
  protected readonly showTokenForm = signal(false)
  protected readonly token = signal('')
  protected readonly busy = signal(false)
  protected readonly confirmingRemove = signal(false)
  protected readonly error = signal<string | null>(null)

  constructor() {
    fromEvent(window, 'focus')
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (this.creationLink() || this.connectLink()) this.refresh()
      })
    interval(CREATION_POLL_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        const link = this.creationLink()
        if (!link) return
        if (Date.parse(link.expiresAt) < Date.now()) this.creationLink.set(null)
        else this.refresh()
      })
  }

  ngOnInit(): void {
    this.namespaceRole
      .isAdminOfNamespace$(this.namespaceId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((admin) => this.canAdminister.set(admin))
    this.messagingState
      .capabilities()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (capabilities) => this.canCreate.set(capabilities.botCreation.includes(this.platform)),
        error: (err) => console.error('[AgentMessaging] Could not read the messaging capabilities:', err),
      })
    this.refresh()
  }

  protected statusLabel(status: MessagingBotStatus): string {
    return STATUS_LABELS[status] ?? status
  }

  protected statusTone(status: MessagingBotStatus): 'ok' | 'off' | 'warn' {
    switch (status) {
      case MessagingBotStatus.RUNNING:
        return 'ok'
      case MessagingBotStatus.DISABLED:
        return 'off'
      default:
        return 'warn'
    }
  }

  protected createBot(): void {
    this.run(
      this.botConfig.requestCreation(this.namespaceId(), this.agentId(), this.platform),
      (link) => this.creationLink.set(link),
      'Could not start the creation of the bot.'
    )
  }

  protected attach(): void {
    const token = this.token().trim()
    if (!token) return
    this.run(
      this.botConfig.attach(this.namespaceId(), this.agentId(), this.platform, token),
      (bot) => {
        this.token.set('')
        this.showTokenForm.set(false)
        this.replace(bot)
      },
      `${this.platformName} refused this token, or the bot already serves another agent.`
    )
  }

  protected toggle(bot: MessagingBotDto): void {
    this.run(
      this.botConfig.setEnabled(bot.id, !bot.enabled),
      (updated) => this.replace(updated),
      'Could not change the bot.'
    )
  }

  protected remove(bot: MessagingBotDto): void {
    this.confirmingRemove.set(false)
    this.run(
      this.botConfig.remove(bot.id),
      () => {
        this.bots.update((bots) => bots.filter((other) => other.id !== bot.id))
        this.messagingState
          .load()
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({ error: () => undefined })
      },
      'Could not remove the bot.'
    )
  }

  protected connect(bot: MessagingBotDto): void {
    this.run(
      this.messagingState.createLink(bot.id),
      (link) => this.connectLink.set(link),
      'Could not get a link to the bot.'
    )
  }

  private refresh(): void {
    this.botConfig
      .agentBots(this.namespaceId(), this.agentId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bots) => {
          this.bots.set(bots)
          if (bots.some((bot) => bot.platform === this.platform)) this.creationLink.set(null)
          if (bots.some((bot) => bot.linked)) this.connectLink.set(null)
        },
        error: (err) => console.error('[AgentMessaging] Could not load the bots of the agent:', err),
      })
  }

  private replace(bot: MessagingBotDto): void {
    this.bots.update((bots) => [...bots.filter((other) => other.platform !== bot.platform), bot])
  }

  private run<T>(call: Observable<T>, onSuccess: (value: T) => void, failure: string): void {
    this.error.set(null)
    this.busy.set(true)
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => {
        this.busy.set(false)
        onSuccess(value)
      },
      error: (err) => {
        console.error('[AgentMessaging]', failure, err)
        this.busy.set(false)
        this.error.set(err?.error?.message ?? failure)
      },
    })
  }
}

const CREATION_POLL_MS = 3000

const STATUS_LABELS: Record<MessagingBotStatus, string> = {
  [MessagingBotStatus.STARTING]: 'Starting',
  [MessagingBotStatus.RUNNING]: 'Running',
  [MessagingBotStatus.DISABLED]: 'Off',
  [MessagingBotStatus.TOKEN_REJECTED]: 'Token rejected',
  [MessagingBotStatus.CONFLICT]: 'Used elsewhere',
  [MessagingBotStatus.UNREACHABLE]: 'Unreachable',
}
