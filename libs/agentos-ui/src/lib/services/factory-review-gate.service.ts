import { HttpClient } from '@angular/common/http'
import { computed, inject, Injectable, OnDestroy, signal } from '@angular/core'
import { interval, Subscription, EMPTY, Observable } from 'rxjs'
import { switchMap, catchError, tap } from 'rxjs/operators'

// ---------------------------------------------------------------------------
// Domain types
// ---------------------------------------------------------------------------

export type ReviewGateDecision = 'retry' | 'ignore' | 'fail' | 'continue'

export interface ReviewerOutcome {
  reviewerName: string
  verdict: 'PASS' | 'FAIL' | null
  hasCritical: boolean
  summary: string | null
}

export type OracleClassification =
  | 'CLEAN'
  | 'PRODUCT_REGRESSION'
  | 'BASELINE_FAILURE'
  | 'ORACLE_INFRASTRUCTURE'
  | 'INDETERMINATE_OUT_OF_SCOPE'

export interface OracleGateInfo {
  oracleName: string
  classification: OracleClassification
  reason: string
  command: string
  cwd: string
  projects: string[]
  baselineDiagnostics: string[]
  postEditDiagnostics: string[]
  newDiagnostics: string[]
  preExistingDiagnostics: string[]
  newDiagnosticLines: string[]
  baselineEvidence: string
}

/**
 * State returned by GET /api/factory/runs/:runId/review-gate.
 *
 * pending  — gate is open, decisions are allowed
 * terminal — gate closed (run finished or no gate active), no decisions allowed
 * null     — not yet loaded (initial state)
 *
 * gateType discriminates between:
 *   'adversarial-review' — existing adversarial reviewer FAIL gate
 *   'oracle'             — deterministic oracle failure gate
 */
export type ReviewGateState =
  | {
      status: 'pending'
      gateType?: 'adversarial-review' | 'oracle'
      findings: string
      outcomes: ReviewerOutcome[]
      oracleGate?: OracleGateInfo
      allowedDecisions: ReviewGateDecision[]
      openedAt?: string
    }
  | {
      status: 'terminal'
      humanDecision: ReviewGateDecision | null
      /** Human-readable explanation of why the gate is terminal. */
      reason: string
    }
  | null

export interface ReviewGateReply {
  decision: ReviewGateDecision
  message?: string
}

const POLL_INTERVAL_MS = 4_000

/**
 * Run-scoped review gate service.
 *
 * Call `startPolling(runId)` when a run with a potential gate is selected.
 * Call `stopPolling()` (or `startPolling` with a new runId) to cancel.
 * Exposes `gate` signal and `sendDecision()` scoped to the current run.
 *
 * Gate lifecycle:
 * - `null`     : not polling or not yet loaded
 * - `pending`  : gate open, human decision required — show decision actions
 * - `terminal` : gate closed — do NOT show decision actions
 *
 * For completed historical runs with humanDecision:fail in their JSONL:
 * the server returns { status:'terminal', humanDecision:'fail', reason:'...' }.
 * A terminated Node process cannot resume. Only future pending gates can
 * receive decisions. Angular must not offer retry/ignore for terminal gates.
 */
@Injectable({ providedIn: 'root' })
export class FactoryReviewGateService implements OnDestroy {
  private readonly http = inject(HttpClient)

  readonly gate = signal<ReviewGateState>(null)
  readonly submitting = signal(false)
  readonly submitError = signal<string | null>(null)

  /** True when gate is pending and actions are allowed. */
  readonly isPending = computed(() => this.gate()?.status === 'pending')

  private currentRunId: string | null = null
  private pollSubscription: Subscription | null = null

  /**
   * Start polling for the given runId.
   * If called with the same runId while already polling, this is a no-op.
   * If called with a different runId, the previous poll is cancelled.
   */
  startPolling(runId: string): void {
    if (this.currentRunId === runId && this.pollSubscription && !this.pollSubscription.closed) return

    this.stopPolling()
    this.currentRunId = runId
    this.gate.set(null)
    this.submitError.set(null)

    this.pollSubscription = interval(POLL_INTERVAL_MS)
      .pipe(
        switchMap(() =>
          this.http
            .get<ReviewGateState>(`/api/factory/runs/${encodeURIComponent(runId)}/review-gate`)
            .pipe(catchError(() => EMPTY))
        ),
        tap((state) => this.gate.set(state))
      )
      .subscribe()
  }

  stopPolling(): void {
    this.pollSubscription?.unsubscribe()
    this.pollSubscription = null
    this.currentRunId = null
    this.gate.set(null)
    this.submitError.set(null)
  }

  /**
   * POST a decision to the run-scoped endpoint.
   * Guards against duplicate submissions via `submitting` signal.
   * Throws if no run is currently being polled.
   */
  sendDecision(decision: ReviewGateDecision, message?: string): Observable<{ ok: boolean; decision: string }> {
    const runId = this.currentRunId
    if (!runId) throw new Error('No active run — call startPolling(runId) first')

    const body: ReviewGateReply = message?.trim() ? { decision, message: message.trim() } : { decision }
    return this.http.post<{ ok: boolean; decision: string }>(
      `/api/factory/runs/${encodeURIComponent(runId)}/review-gate/reply`,
      body
    )
  }

  ngOnDestroy(): void {
    this.stopPolling()
  }
}
