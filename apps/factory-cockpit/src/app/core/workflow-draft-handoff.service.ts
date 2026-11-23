import { Injectable } from '@angular/core'
import { FullWorkflowDefinition } from './factory-api.service'

/**
 * One-shot hand-off of an unsaved workflow definition between two pages, for example the draft of an expanded run
 * opened in the workflow composer. The draft lives in memory only: it is consumed once and never persisted.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowDraftHandoffService {
  private pending: FullWorkflowDefinition | null = null

  /** Park a draft for the next page that takes it (replaces any previous one). */
  set(draft: FullWorkflowDefinition): void {
    this.pending = draft
  }

  /** Return the parked draft, if any, and forget it. */
  take(): FullWorkflowDefinition | null {
    const draft = this.pending
    this.pending = null
    return draft
  }
}
