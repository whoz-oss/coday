import { DatePipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import {
  MessagingAppManifestDto,
  MessagingBotDto,
  MessagingBotStatus,
  MessagingLinkDto,
  MessagingPlatform,
} from '@whoz-oss/agentos-api-client'
import { fromEvent, interval, Observable } from 'rxjs'
import { MessagingBotConfigService } from '../../services/messaging-bot-config.service'
import { MessagingStateService } from '../../services/messaging-state.service'
import { NamespaceRoleStateService } from '../../services/namespace-role-state.service'
import { MESSAGING_PLATFORMS, messagingPlatformName } from '../messaging-accounts/messaging-platforms'

/**
 * AgentMessagingComponent — the "Messaging" section of an agent's page: the agent's bots, one per platform,
 * which its users talk to it through.
 *
 * An admin of the namespace gives the agent a bot, taken into account at once by the server, with no restart:
 * - Telegram, Create a bot: a single-use link to the instance's bot manager, which creates the bot once the admin
 *   confirms in Telegram, and links the admin's account to it. Offered when the instance has a bot manager.
 * - Telegram, Use an existing bot: the token of a bot created with BotFather.
 * - Slack: the admin creates a Slack app from the manifest the section shows, installs it, and pastes its two
 *   tokens.
 *
 * While a creation is pending, the section reads the bots again every few seconds and when the window gets the
 * focus back: the admin confirms in Telegram, often on their phone. Every user may connect their own account to
 * a bot from here or from their profile.
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

  protected readonly platforms = MESSAGING_PLATFORMS
  protected readonly platformName = messagingPlatformName
  protected readonly Platform = MessagingPlatform

  protected readonly bots = signal<MessagingBotDto[]>([])
  protected readonly canAdminister = signal(false)

  /** The platforms whose bot can be created with no token: those with a running bot manager. */
  protected readonly creatable = signal<MessagingPlatform[]>([])

  protected readonly creationLink = signal<MessagingLinkDto | null>(null)
  /** The link issued to connect the user's account, by bot, until the account shows up. */
  protected readonly connectLinks = signal<Record<string, MessagingLinkDto>>({})

  /** The platform whose "existing bot" form is open. */
  protected readonly formPlatform = signal<MessagingPlatform | null>(null)
  protected readonly token = signal('')
  protected readonly appToken = signal('')
  protected readonly manifest = signal<MessagingAppManifestDto | null>(null)
  protected readonly copied = signal(false)

  protected readonly busy = signal(false)
  protected readonly confirmingRemoveId = signal<string | null>(null)
  protected readonly error = signal<string | null>(null)

  constructor() {
    fromEvent(window, 'focus')
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (this.creationLink() || Object.keys(this.connectLinks()).length > 0) this.refresh()
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
        next: (capabilities) => this.creatable.set(capabilities.botCreation),
        error: (err) => console.error('[AgentMessaging] Could not read the messaging capabilities:', err),
      })
    this.refresh()
  }

  protected botOf(platform: MessagingPlatform): MessagingBotDto | null {
    return this.bots().find((bot) => bot.platform === platform) ?? null
  }

  protected canCreate(platform: MessagingPlatform): boolean {
    return this.creatable().includes(platform)
  }

  protected connectLinkOf(bot: MessagingBotDto): MessagingLinkDto | undefined {
    return this.connectLinks()[bot.id]
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

  protected openForm(platform: MessagingPlatform): void {
    if (this.formPlatform() === platform) {
      this.formPlatform.set(null)
      return
    }
    this.formPlatform.set(platform)
    this.token.set('')
    this.appToken.set('')
    this.manifest.set(null)
    this.copied.set(false)
    if (platform === MessagingPlatform.SLACK) {
      this.run(
        this.botConfig.manifest(this.namespaceId(), this.agentId(), platform),
        (manifest) => this.manifest.set(manifest),
        'Could not get the manifest of the Slack app.'
      )
    }
  }

  protected copy(text: string): void {
    navigator.clipboard?.writeText(text).then(
      () => this.copied.set(true),
      () => this.copied.set(false)
    )
  }

  protected createBot(platform: MessagingPlatform): void {
    this.run(
      this.botConfig.requestCreation(this.namespaceId(), this.agentId(), platform),
      (link) => this.creationLink.set(link),
      'Could not start the creation of the bot.'
    )
  }

  protected attach(platform: MessagingPlatform): void {
    const token = this.token().trim()
    const appToken = this.appToken().trim()
    if (!token || (platform === MessagingPlatform.SLACK && !appToken)) return
    this.run(
      this.botConfig.attach(this.namespaceId(), this.agentId(), platform, token, appToken || undefined),
      (bot) => {
        this.token.set('')
        this.appToken.set('')
        this.formPlatform.set(null)
        this.replace(bot)
      },
      `${messagingPlatformName(platform)} refused these tokens, or the bot already serves another agent.`
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
    this.confirmingRemoveId.set(null)
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
      (link) => this.connectLinks.update((links) => ({ ...links, [bot.id]: link })),
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
          if (bots.some((bot) => bot.platform === MessagingPlatform.TELEGRAM)) this.creationLink.set(null)
          this.connectLinks.update((links) =>
            Object.fromEntries(
              Object.entries(links).filter(([botId]) => !bots.some((bot) => bot.id === botId && bot.linked))
            )
          )
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
