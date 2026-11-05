import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core'
import { CaseControllerService } from '@whoz-oss/agentos-api-client'
import { CompanionNavigationService } from '../../services/companion-navigation.service'
import {
  CompanionStateService,
  CaseGroup,
  ConfirmationNotification,
  QuestionNotification,
} from '../../services/companion-state.service'

/**
 * Companion notification renderer — displayed inside the Document Picture-in-Picture
 * window by CompanionPipService.
 *
 * This component is now a pure renderer: it reads notification groups from
 * CompanionStateService (which runs in the main document context) and delegates
 * all state mutations back to it. The SSE subscription, sub-case filtering, and
 * PiP window lifecycle are all managed by CompanionStateService, solving the
 * chicken-and-egg problem where the SSE listener previously lived inside this
 * component (which only exists once the PiP is already open).
 */
@Component({
  selector: 'agentos-companion',
  templateUrl: './companion.component.html',
  styleUrl: './companion.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanionComponent {
  private readonly companionState = inject(CompanionStateService)
  private readonly caseController = inject(CaseControllerService)
  private readonly companionNav = inject(CompanionNavigationService)

  /**
   * Created by CompanionPipService synchronously inside the triggering click handler
   * (before the requestWindow() await) so the browser's autoplay policy treats beeps
   * played later as still tied to that user gesture.
   */
  readonly audioContext = input<AudioContext | null>(null)

  /** Notification groups — read directly from the shared service signal. */
  protected readonly groups = this.companionState.groups

  // ---------------------------------------------------------------------------
  // User interactions
  // ---------------------------------------------------------------------------

  protected dismissGroup(caseId: string): void {
    this.companionState.dismissGroup(caseId)
  }

  protected dismissOne(caseId: string, notifId: string, event: Event): void {
    event.stopPropagation()
    this.companionState.removeNotification(caseId, notifId)
  }

  protected jumpToCase(group: CaseGroup): void {
    this.companionNav.navigateTo(group.namespaceId, group.caseId)
    const hasBlocking = group.notifications.some((n) => n.blocking)
    if (!hasBlocking) {
      this.companionState.dismissGroup(group.caseId)
    }
  }

  protected approve(n: ConfirmationNotification): void {
    this.respond(n, 'yes')
  }

  protected deny(n: ConfirmationNotification): void {
    this.respond(n, 'no')
  }

  protected answerQuestion(n: QuestionNotification, option: string): void {
    this.respond(n, option)
  }

  private respond(n: { id: string; caseId: string; namespaceId: string }, content: string): void {
    this.companionState.removeNotification(n.caseId, n.id)
    this.caseController.addMessageCase(n.caseId, { content, answerToEventId: n.id }).subscribe({
      error: (err) => console.error('[Companion] failed to send response', err),
    })
  }

  // ---------------------------------------------------------------------------
  // Audio
  // ---------------------------------------------------------------------------

  playAlertBeep(): void {
    const ctx = this.audioContext()
    if (!ctx) return
    try {
      const oscillator = ctx.createOscillator()
      const gain = ctx.createGain()
      oscillator.type = 'sine'
      oscillator.frequency.value = 880
      gain.gain.setValueAtTime(0.15, ctx.currentTime)
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.25)
      oscillator.connect(gain)
      gain.connect(ctx.destination)
      oscillator.start()
      oscillator.stop(ctx.currentTime + 0.25)
    } catch (err) {
      console.warn('[Companion] failed to play alert beep', err)
    }
  }
}
