import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core'
import { QuestionEvent, QuestionEventQuestionTypeEnum } from '@whoz-oss/agentos-api-client'
import { OAuthAgentosService } from '../../services/oauth-agentos.service'

/**
 * QuestionPanelComponent — renders an inline interactive panel for a QuestionEvent.
 *
 * Supports four question types:
 * - FREE_TEXT: text input + submit button
 * - SINGLE_CHOICE: one button per option (click = immediate answer)
 * - OPEN_CHOICE: option buttons + free-text fallback
 * - OAUTH_AUTHORIZE: "Authorize" + "Cancel" buttons
 *
 * This is a presentational component for FREE_TEXT / SINGLE_CHOICE / OPEN_CHOICE.
 * For OAUTH_AUTHORIZE the component delegates popup management to OAuthAgentosService
 * (which must be called from a direct click handler for browser popup-blocker compliance).
 *
 * Emits `answered` when the user submits a response (all types except OAUTH_AUTHORIZE
 * where the service handles the submission).
 * Emits `cancelled` when the user dismisses the panel.
 */
@Component({
  selector: 'agentos-question-panel',
  templateUrl: './question-panel.component.html',
  styleUrl: './question-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QuestionPanelComponent {
  private readonly oauthService = inject(OAuthAgentosService)

  readonly questionEvent = input.required<QuestionEvent>()

  /** Emitted when the user submits an answer (not emitted for OAUTH_AUTHORIZE). */
  readonly answered = output<string>()
  /** Emitted when the user cancels / dismisses the panel. */
  readonly cancelled = output<void>()

  protected readonly QuestionType = QuestionEventQuestionTypeEnum

  /** Free-text input value (used for FREE_TEXT and OPEN_CHOICE custom answer). */
  protected readonly freeTextValue = signal('')

  protected onFreeTextInput(event: Event): void {
    this.freeTextValue.set((event.target as HTMLInputElement).value)
  }

  protected onFreeTextKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      this.submitFreeText()
    }
  }

  protected submitFreeText(): void {
    const value = this.freeTextValue().trim()
    if (!value) return
    this.answered.emit(value)
  }

  protected selectOption(option: string): void {
    this.answered.emit(option)
  }

  protected onAuthorize(): void {
    // Must be called from a click handler — OAuthAgentosService.openPopup() opens the popup.
    this.oauthService.openPopup(this.questionEvent())
    // The panel hides itself after openPopup() sets pendingQuestion to null.
    // The parent (CaseChatComponent) watches pendingQuestion and removes the panel.
  }

  protected onOAuthCancel(): void {
    // Only for OAUTH_AUTHORIZE: clear the pending question in the service.
    this.oauthService.cancelRequest()
    this.cancelled.emit()
  }
}
