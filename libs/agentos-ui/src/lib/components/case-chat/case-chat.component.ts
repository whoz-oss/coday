import { Component, signal } from '@angular/core'
import { IconButtonComponent } from '@whoz-oss/design-system'

/**
 * CaseChatComponent — real-time chat view for an active case.
 *
 * Displays the event stream for a case:
 * - MessageEvent (user vs agent, distinguished visually)
 * - ThinkingEvent (animated indicator while agent is processing)
 * - QuestionEvent (inline question with options or free input)
 *
 * TODO: inject CaseStateService once agentos-dataflow is available
 *   - subscribe to events$ for message history + live events
 *   - subscribe to isRunning$ to show/hide thinking indicator
 *   - subscribe to pendingQuestion$ to render QuestionEvent
 *   - call caseState.loadCase(caseId) on init (read :caseId from ActivatedRoute)
 *   - call caseState.sendMessage(content) on submit
 *   - call caseState.stop() on stop action
 * TODO: inject ActivatedRoute to read :caseId and :namespaceId params
 */
@Component({
  selector: 'agentos-case-chat',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './case-chat.component.html',
  styleUrl: './case-chat.component.scss',
})
export class CaseChatComponent {
  protected inputValue = signal('')
  // TODO: replace with CaseStateService.isRunning$
  protected isRunning = signal(false)

  protected get canSend(): boolean {
    return !!this.inputValue().trim() && !this.isRunning()
  }

  protected onInput(event: Event): void {
    this.inputValue.set((event.target as HTMLTextAreaElement).value)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      this.submit()
    }
  }

  protected submit(): void {
    if (!this.canSend) return
    // TODO: caseState.sendMessage(this.inputValue().trim())
    this.inputValue.set('')
  }

  protected stop(): void {
    // TODO: caseState.stop()
  }
}
