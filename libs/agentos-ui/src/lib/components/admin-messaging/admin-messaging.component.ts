import { DatePipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { MessagingBotManagerDto, MessagingBotStatus, MessagingPlatform } from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'
import { MessagingBotConfigService } from '../../services/messaging-bot-config.service'
import { messagingPlatformName } from '../messaging-accounts/messaging-platforms'

/**
 * AdminMessagingComponent — Settings › Messaging, for super-admins: the bot manager of the instance on
 * Telegram, the bot that creates the bots of agents for their admins so that nobody copies a token.
 *
 * The manager is set once from its token, and takes effect at once, with no restart. Without it, an agent can
 * still be given a bot created with BotFather, from its token.
 */
@Component({
  selector: 'agentos-admin-messaging',
  imports: [DatePipe],
  templateUrl: './admin-messaging.component.html',
  styleUrl: './admin-messaging.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminMessagingComponent implements OnInit {
  private readonly botConfig = inject(MessagingBotConfigService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly platform = MessagingPlatform.TELEGRAM
  protected readonly platformName = messagingPlatformName(MessagingPlatform.TELEGRAM)

  protected readonly managers = signal<MessagingBotManagerDto[]>([])
  protected readonly manager = computed(
    () => this.managers().find((manager) => manager.platform === this.platform) ?? null
  )

  protected readonly replacing = signal(false)
  protected readonly confirmingRemove = signal(false)
  protected readonly token = signal('')
  protected readonly busy = signal(false)
  protected readonly error = signal<string | null>(null)

  ngOnInit(): void {
    this.run(this.botConfig.managers(), (managers) => this.managers.set(managers), 'Could not load the bot managers.')
  }

  protected statusLabel(status: MessagingBotStatus): string {
    return status === MessagingBotStatus.RUNNING ? 'Running' : status.replace('_', ' ').toLowerCase()
  }

  protected save(): void {
    const token = this.token().trim()
    if (!token) return
    this.run(
      this.botConfig.setManager(this.platform, token),
      (manager) => {
        this.token.set('')
        this.replacing.set(false)
        this.managers.update((managers) => [
          ...managers.filter((other) => other.platform !== manager.platform),
          manager,
        ])
      },
      `${this.platformName} refused this token, or the bot cannot manage other bots yet.`
    )
  }

  protected remove(): void {
    this.confirmingRemove.set(false)
    this.run(
      this.botConfig.removeManager(this.platform),
      () => this.managers.update((managers) => managers.filter((manager) => manager.platform !== this.platform)),
      'Could not remove the bot manager.'
    )
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
        console.error('[AdminMessaging]', failure, err)
        this.busy.set(false)
        this.error.set(err?.error?.message ?? failure)
      },
    })
  }
}
