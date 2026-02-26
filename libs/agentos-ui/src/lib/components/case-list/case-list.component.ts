import { Component, signal } from '@angular/core'

/**
 * CaseListComponent — lists cases for the selected namespace.
 *
 * The textarea at the bottom follows the agentic pattern:
 * no separate "New case" button — the first message creates the case.
 *
 * TODO: inject CaseStateService once agentos-dataflow is available
 *   - display cases$ list
 *   - on submit: call caseState.createCase(), then navigate to /:namespaceId/cases/:caseId
 * TODO: inject ActivatedRoute to read :namespaceId param
 */
@Component({
  selector: 'agentos-case-list',
  standalone: true,
  imports: [],
  templateUrl: './case-list.component.html',
  styleUrl: './case-list.component.scss',
})
export class CaseListComponent {
  protected inputValue = signal('')

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
    const content = this.inputValue().trim()
    if (!content) return
    // TODO: caseState.createCase(content).then(caseId => navigate([namespaceId, 'cases', caseId]))
    this.inputValue.set('')
  }
}
