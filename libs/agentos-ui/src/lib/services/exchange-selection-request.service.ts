import { Injectable, signal } from '@angular/core'
import { ExchangeFileRef } from './exchange-state.service'

export type ExchangeSelectionRequestStatus = 'waiting' | 'proposed' | 'answered' | 'failed'

/** One "ask an agent about this selection" request, from the moment it is sent to the moment it is settled. */
export interface ExchangeSelectionRequest {
  id: string
  ref: ExchangeFileRef
  /** Offsets of the frozen selection in the editable text of the document (0 and 0 when they are unknown). */
  from: number
  to: number
  selection: string
  /** Markdown source of the selection, when it was made in the rendered markdown (what the agent was shown). */
  selectionMarkdown?: string
  /** Markup of the selection, when it was made in an HTML page (what the agent was shown). */
  selectionHtml?: string
  instruction: string
  status: ExchangeSelectionRequestStatus
  /** The replacement text proposed by the agent, when `status` is 'proposed'. */
  proposal?: string
  /** The agent's explanation ('proposed') or its whole answer ('answered'), or the error ('failed'). */
  answer?: string
}

export type NewExchangeSelectionRequest = Omit<ExchangeSelectionRequest, 'id' | 'status' | 'proposal' | 'answer'>

/** The marker the message carries so the answer can be tied back to the request. */
export function selectionRequestMarker(id: string): string {
  return `[selection-request:${id}]`
}

function sameFile(a: ExchangeFileRef, b: ExchangeFileRef): boolean {
  return a.scope === b.scope && a.path === b.path
}

/**
 * Pending requests of agent runs on a selection of an exchange document.
 *
 * Root service: the chat registers a request when it sends the message and settles it from the event stream, while
 * the viewer (through the shell) only reads the request of the file it shows. Nothing is persisted: a request lives
 * until the user accepts, rejects or dismisses it, or the page is left.
 *
 * Transitions: waiting → proposed | answered | failed. A request that is not waiting any more ignores later answers,
 * so a stale event cannot overwrite what the user is looking at.
 */
@Injectable({ providedIn: 'root' })
export class ExchangeSelectionRequestService {
  private readonly _requests = signal<ExchangeSelectionRequest[]>([])
  readonly requests = this._requests.asReadonly()

  private counter = 0

  /** Registers a request that waits for the agent, and returns it. Starting another for the same file replaces it. */
  register(request: NewExchangeSelectionRequest): ExchangeSelectionRequest {
    const created: ExchangeSelectionRequest = {
      ...request,
      id: `${Date.now().toString(36)}${(this.counter++).toString(36)}`,
      status: 'waiting',
    }
    this._requests.update((current) => [...current.filter((r) => !sameFile(r.ref, request.ref)), created])
    return created
  }

  /** The request of a file, if any. */
  forFile(ref: ExchangeFileRef | null): ExchangeSelectionRequest | null {
    if (!ref) return null
    return this._requests().find((r) => sameFile(r.ref, ref)) ?? null
  }

  get(id: string): ExchangeSelectionRequest | null {
    return this._requests().find((r) => r.id === id) ?? null
  }

  /** Whether a request waits for the agent or for the user's decision on this file: the document must not be reloaded. */
  hasPending(ref: ExchangeFileRef | null): boolean {
    return this.forFile(ref) !== null
  }

  /** The oldest request that still waits for an agent answer. */
  firstWaiting(): ExchangeSelectionRequest | null {
    return this._requests().find((r) => r.status === 'waiting') ?? null
  }

  propose(id: string, proposal: string, explanation: string): void {
    this.settle(id, { status: 'proposed', proposal, answer: explanation })
  }

  answer(id: string, answer: string): void {
    this.settle(id, { status: 'answered', answer })
  }

  fail(id: string, error: string): void {
    this.settle(id, { status: 'failed', answer: error })
  }

  /** Accepted, rejected or dismissed: the request and its highlight go away. */
  clear(id: string): void {
    this._requests.update((current) => current.filter((r) => r.id !== id))
  }

  clearAll(): void {
    this._requests.set([])
  }

  /** Fails every request that still waits, e.g. when the case ends. */
  failWaiting(error: string): void {
    this._requests.update((current) =>
      current.map((r) => (r.status === 'waiting' ? { ...r, status: 'failed' as const, answer: error } : r))
    )
  }

  private settle(id: string, patch: Partial<ExchangeSelectionRequest>): void {
    this._requests.update((current) =>
      current.map((r) => (r.id === id && r.status === 'waiting' ? { ...r, ...patch } : r))
    )
  }
}
