import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { MessagingBotDto } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { MessagingStateService } from '../../services/messaging-state.service'
import { messagingPlatformName } from '../messaging-accounts/messaging-platforms'

type ContinueStatus = 'idle' | 'sending' | 'sent' | 'failed'

/**
 * CaseContinueMessagingComponent — "Continue on Telegram" in the header of a case: moves the user's direct
 * chat with an agent's bot to this case and posts its last answer there, so that the user goes on from their
 * phone, with that agent.
 *
 * One button per bot of the case's namespace the user has linked an account to; nothing when there is none.
 * The button shows the outcome for a few seconds: there is no toast service in this library.
 */
@Component({
  selector: 'agentos-case-continue-messaging',
  imports: [IconButtonComponent],
  templateUrl: './case-continue-messaging.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseContinueMessagingComponent {
  private readonly messagingState = inject(MessagingStateService)
  private readonly destroyRef = inject(DestroyRef)

  readonly caseId = input.required<string>()
  readonly namespaceId = input.required<string>()

  /** The bots of the case's namespace the user has linked an account to: they answer in this case's namespace. */
  protected readonly bots = computed(() =>
    this.messagingState.bots().filter((bot) => bot.linked && bot.namespaceId === this.namespaceId())
  )

  protected readonly status = signal<Record<string, ContinueStatus>>({})

  constructor() {
    this.messagingState
      .ensureLoaded()
      .pipe(takeUntilDestroyed())
      .subscribe({
        error: (err) => console.error('[CaseContinueMessaging] Could not load the messaging accounts:', err),
      })
  }

  protected icon(bot: MessagingBotDto): string {
    switch (this.status()[bot.id]) {
      case 'sent':
        return 'check'
      case 'failed':
        return 'error_outline'
      default:
        return 'send_to_mobile'
    }
  }

  protected title(bot: MessagingBotDto): string {
    const where = `${messagingPlatformName(bot.platform)} with ${bot.agentName} (@${bot.username})`
    switch (this.status()[bot.id]) {
      case 'sending':
        return `Sending to ${where}…`
      case 'sent':
        return `Sent: continue the case in ${where}`
      case 'failed':
        return `Could not send the case to ${where}`
      default:
        return `Continue on ${where}`
    }
  }

  protected continueWith(bot: MessagingBotDto): void {
    if (this.status()[bot.id] === 'sending') return
    this.setStatus(bot.id, 'sending')
    this.messagingState
      .continueCase(this.caseId(), bot.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.showOutcome(bot.id, 'sent'),
        error: (err) => {
          console.error(`[CaseContinueMessaging] Could not continue case ${this.caseId()} with @${bot.username}:`, err)
          this.showOutcome(bot.id, 'failed')
        },
      })
  }

  private showOutcome(botId: string, outcome: ContinueStatus): void {
    this.setStatus(botId, outcome)
    const timer = setTimeout(() => this.setStatus(botId, 'idle'), OUTCOME_DISPLAY_MS)
    this.destroyRef.onDestroy(() => clearTimeout(timer))
  }

  private setStatus(botId: string, status: ContinueStatus): void {
    this.status.update((current) => ({ ...current, [botId]: status }))
  }
}

const OUTCOME_DISPLAY_MS = 4000
