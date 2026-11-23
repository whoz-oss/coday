import { Injectable } from '@angular/core'
import { FullWorkflowDefinition } from './factory-api.service'

/**
 * One-time handoff of a workflow draft between two pages (for example a run page that offers to save the
 * definition it followed, and the workflow composer). The draft is consumed by the first `take()`, so a later
 * visit to the composer always starts from a blank workflow.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowDraftHandoffService {
  private draft: FullWorkflowDefinition | null = null

  set(draft: FullWorkflowDefinition): void {
    this.draft = draft
  }

  take(): FullWorkflowDefinition | null {
    const draft = this.draft
    this.draft = null
    return draft
  }
}
