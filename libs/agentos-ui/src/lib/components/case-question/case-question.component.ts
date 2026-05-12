import { Component, EventEmitter, Input, Output } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { MatIcon } from '@angular/material/icon'
import { QuestionEvent } from '@whoz-oss/agentos-api-client'

/**
 * Inline confirmation widget for a WZ-31596 `QuestionEvent`.
 *
 * Renders:
 * - the question text + agent attribution
 * - one button per option in [event.options] (e.g. "Confirmer", "Annuler")
 * - a textarea fallback when options is empty/null
 *
 * Behaviour:
 * - Clicking a button emits the option value via [answer].
 * - When [respondedAnswer] is set (by the parent on replay or after an in-session
 *   click), the widget locks: buttons are disabled and a confirmation indicator
 *   "✓ Réponse envoyée : <option>" appears.
 *
 * Wired into `CaseChatComponent`'s timeline as a `kind: 'question'` item.
 */
@Component({
  selector: 'agentos-case-question',
  standalone: true,
  imports: [FormsModule, MatIcon],
  templateUrl: './case-question.component.html',
  styleUrl: './case-question.component.scss',
})
export class CaseQuestionComponent {
  @Input() event!: QuestionEvent
  @Input() respondedAnswer: string | null = null

  @Output() readonly answer = new EventEmitter<{ questionId: string; answer: string }>()

  customAnswer: string = ''

  get options(): string[] {
    return this.event?.options ?? []
  }

  get hasOptions(): boolean {
    return this.options.length > 0
  }

  get isLocked(): boolean {
    return this.respondedAnswer !== null && this.respondedAnswer !== undefined
  }

  onOptionClick(option: string): void {
    if (this.isLocked) return
    this.respondedAnswer = option
    this.answer.emit({ questionId: this.event.id, answer: option })
  }

  onCustomSubmit(): void {
    if (this.isLocked) return
    const trimmed = this.customAnswer.trim()
    if (!trimmed) return
    this.respondedAnswer = trimmed
    this.customAnswer = ''
    this.answer.emit({ questionId: this.event.id, answer: trimmed })
  }

  onCustomKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      this.onCustomSubmit()
    }
  }
}
