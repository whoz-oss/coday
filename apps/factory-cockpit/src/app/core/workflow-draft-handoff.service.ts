import { Injectable } from '@angular/core'
import { FullWorkflowDefinition } from './factory-api.service'

/** A draft not read by the composer within this delay is considered abandoned. */
export const DRAFT_HANDOFF_TTL_MS = 30_000

/**
 * One-time handoff of a workflow draft between two pages (for example a run page that offers to save the
 * definition it followed, and the workflow composer).
 *
 * A draft never outlives its handoff:
 *  - it is consumed by the first `take()`;
 *  - the caller clears it with `clear()` when the navigation does not happen (cancelled, superseded, failed);
 *  - as a last resort, `take()` ignores a draft older than {@link DRAFT_HANDOFF_TTL_MS}.
 * A later, unrelated visit to the composer therefore starts from a blank workflow.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowDraftHandoffService {
  private pending: { draft: FullWorkflowDefinition; setAt: number } | null = null

  set(draft: FullWorkflowDefinition): void {
    this.pending = { draft, setAt: Date.now() }
  }

  take(): FullWorkflowDefinition | null {
    const pending = this.pending
    this.pending = null
    return pending && Date.now() - pending.setAt <= DRAFT_HANDOFF_TTL_MS ? pending.draft : null
  }

  /** Drop the draft, if any, without reading it. */
  clear(): void {
    this.pending = null
  }
}
