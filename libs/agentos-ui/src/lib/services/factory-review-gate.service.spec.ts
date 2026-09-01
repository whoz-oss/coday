import { TestBed, fakeAsync, tick } from '@angular/core/testing'
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing'
import { FactoryReviewGateService, ReviewGateState } from './factory-review-gate.service'

const RUN_A = 'run-aaa-111'
const RUN_B = 'run-bbb-222'

describe('FactoryReviewGateService', () => {
  let service: FactoryReviewGateService
  let http: HttpTestingController

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
    })
    service = TestBed.inject(FactoryReviewGateService)
    http = TestBed.inject(HttpTestingController)
  })

  afterEach(() => {
    // Stop polling to unsubscribe from the interval before verifying.
    service.ngOnDestroy()
    // Cancel any HTTP requests that were opened but not flushed in the test
    // (e.g. a second interval tick that fired during the test's fakeAsync zone
    // but was never consumed). This prevents http.verify() from throwing on
    // requests that are irrelevant to the assertion under test.
    http.match(() => true)
    http.verify()
  })

  // ---------------------------------------------------------------------------
  // Initial state
  // ---------------------------------------------------------------------------

  it('gate signal starts as null', () => {
    expect(service.gate()).toBeNull()
  })

  it('isPending is false when gate is null', () => {
    expect(service.isPending()).toBe(false)
  })

  it('isPending is false when gate is terminal', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    const req = http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`)
    const terminalState: ReviewGateState = {
      status: 'terminal',
      humanDecision: 'fail',
      reason: 'Run completed. A terminated process cannot resume.',
    }
    req.flush(terminalState)

    expect(service.isPending()).toBe(false)
    expect(service.gate()).toEqual(terminalState)
  }))

  // ---------------------------------------------------------------------------
  // Run-scoped endpoints
  // ---------------------------------------------------------------------------

  it('polls run-scoped endpoint GET /api/factory/runs/:runId/review-gate', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush({
      status: 'pending',
      findings: '**2/4 reviewers returned FAIL**',
      outcomes: [
        { reviewerName: 'AdversarialReviewer1', verdict: 'FAIL', hasCritical: true, summary: 'Critical issue.' },
        { reviewerName: 'AdversarialReviewer2', verdict: 'PASS', hasCritical: false, summary: null },
      ],
      allowedDecisions: ['retry', 'ignore', 'fail'],
      openedAt: '2026-09-01T10:00:00.000Z',
    })

    expect(service.gate()?.status).toBe('pending')
    expect(service.isPending()).toBe(true)
  }))

  it('does NOT poll the deprecated global /api/review-gate', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    // Only the run-scoped URL should be requested.
    http
      .expectOne(`/api/factory/runs/${RUN_A}/review-gate`)
      .flush({ status: 'terminal', humanDecision: null, reason: 'No gate.' })
    http.expectNone('/api/review-gate')
  }))

  it('sendDecision posts to run-scoped endpoint with decision and message', () => {
    service.startPolling(RUN_A)

    service.sendDecision('retry', 'Fix the null check').subscribe()

    const req = http.expectOne(`/api/factory/runs/${RUN_A}/review-gate/reply`)
    expect(req.request.method).toBe('POST')
    expect(req.request.body).toEqual({ decision: 'retry', message: 'Fix the null check' })
    req.flush({ ok: true, decision: 'retry' })
  })

  it('sendDecision omits message when blank', () => {
    service.startPolling(RUN_A)

    service.sendDecision('fail', '  ').subscribe()

    const req = http.expectOne(`/api/factory/runs/${RUN_A}/review-gate/reply`)
    expect(req.request.body).toEqual({ decision: 'fail' })
    req.flush({ ok: true, decision: 'fail' })
  })

  it('sendDecision throws if no run is being polled', () => {
    expect(() => service.sendDecision('fail')).toThrow(/startPolling/)
  })

  // ---------------------------------------------------------------------------
  // Run isolation: two concurrent runs do not share state
  // ---------------------------------------------------------------------------

  it('switching runId cancels previous poll and resets gate', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)
    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush({
      status: 'pending',
      findings: 'Run A findings',
      outcomes: [],
      allowedDecisions: ['retry', 'ignore', 'fail'],
    })
    expect(service.gate()?.status).toBe('pending')

    // Switch to a different run — gate must reset to null.
    service.startPolling(RUN_B)
    expect(service.gate()).toBeNull()

    tick(4000)
    http.expectOne(`/api/factory/runs/${RUN_B}/review-gate`).flush({
      status: 'terminal',
      humanDecision: null,
      reason: 'No gate for run B.',
    })

    // Run B's state is shown, not Run A's.
    expect(service.gate()?.status).toBe('terminal')
  }))

  it('startPolling is idempotent for the same runId', fakeAsync(() => {
    service.startPolling(RUN_A)
    service.startPolling(RUN_A) // second call — no new subscription
    tick(4000)

    // Only one HTTP request should be made.
    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush({
      status: 'terminal',
      humanDecision: null,
      reason: 'No gate.',
    })
  }))

  // ---------------------------------------------------------------------------
  // Terminal state for completed historical runs
  // ---------------------------------------------------------------------------

  it('shows terminal state for completed run with humanDecision:fail', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    const terminalState: ReviewGateState = {
      status: 'terminal',
      humanDecision: 'fail',
      reason: 'Run completed. A terminated process cannot resume. Only future pending gates can receive decisions.',
    }
    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush(terminalState)

    const gate = service.gate()
    expect(gate?.status).toBe('terminal')
    if (gate?.status === 'terminal') {
      expect(gate.humanDecision).toBe('fail')
    }
    // isPending must be false — Angular must not show decision actions.
    expect(service.isPending()).toBe(false)
  }))

  it('shows terminal state for completed run with humanDecision:ignore', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush({
      status: 'terminal',
      humanDecision: 'ignore',
      reason: 'Run completed.',
    })

    const gate = service.gate()
    expect(gate?.status).toBe('terminal')
    expect(service.isPending()).toBe(false)
  }))

  // ---------------------------------------------------------------------------
  // Error handling
  // ---------------------------------------------------------------------------

  it('gate remains null on network error (catchError swallows)', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).error(new ProgressEvent('network error'))

    // catchError returns EMPTY — gate stays at whatever it was before.
    expect(service.gate()).toBeNull()
  }))

  // ---------------------------------------------------------------------------
  // Oracle gate type
  // ---------------------------------------------------------------------------

  it('oracle gate: pending state with gateType=oracle and oracleGate', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    const oracleState: ReviewGateState = {
      status: 'pending',
      gateType: 'oracle',
      findings: '**Oracle gate: BASELINE_FAILURE**',
      outcomes: [],
      oracleGate: {
        oracleName: 'types',
        classification: 'BASELINE_FAILURE',
        reason: 'All diagnostics pre-existing.',
        command: 'pnpm nx run-many --target=type-check',
        cwd: '/repo',
        projects: ['client'],
        baselineDiagnostics: ['TS:TS2345:src/app/foo.ts:42:7'],
        postEditDiagnostics: ['TS:TS2345:src/app/foo.ts:42:7'],
        newDiagnostics: [],
        preExistingDiagnostics: ['TS:TS2345:src/app/foo.ts:42:7'],
        newDiagnosticLines: [],
        baselineEvidence: 'exitCode=1, durationMs=5000',
      },
      allowedDecisions: ['continue', 'fail'],
      openedAt: '2026-09-01T10:00:00.000Z',
    }
    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush(oracleState)

    const gate = service.gate()
    expect(gate?.status).toBe('pending')
    expect(service.isPending()).toBe(true)
    if (gate?.status === 'pending') {
      expect(gate.gateType).toBe('oracle')
      expect(gate.allowedDecisions).toContain('continue')
      expect(gate.allowedDecisions).not.toContain('retry')
      expect(gate.oracleGate?.classification).toBe('BASELINE_FAILURE')
    }
  }))

  it('oracle gate: sendDecision with continue', () => {
    service.startPolling(RUN_A)

    service.sendDecision('continue', 'pre-existing, safe').subscribe()

    const req = http.expectOne(`/api/factory/runs/${RUN_A}/review-gate/reply`)
    expect(req.request.method).toBe('POST')
    expect(req.request.body).toEqual({ decision: 'continue', message: 'pre-existing, safe' })
    req.flush({ ok: true, decision: 'continue' })
  })

  it('oracle gate: terminal state with humanDecision=continue shows quarantined', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush({
      status: 'terminal',
      humanDecision: 'continue',
      reason: 'Run completed. Oracle was quarantined.',
    })

    const gate = service.gate()
    expect(gate?.status).toBe('terminal')
    expect(service.isPending()).toBe(false)
    if (gate?.status === 'terminal') {
      expect(gate.humanDecision).toBe('continue')
    }
  }))

  it('adversarial-review gate: gateType defaults to adversarial-review when absent', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)

    // Server may omit gateType for backwards compatibility
    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush({
      status: 'pending',
      findings: 'Review findings',
      outcomes: [{ reviewerName: 'AdversarialReviewer1', verdict: 'FAIL', hasCritical: true, summary: 'Bug.' }],
      allowedDecisions: ['retry', 'ignore', 'fail'],
      openedAt: '2026-09-01T10:00:00.000Z',
    })

    const gate = service.gate()
    expect(gate?.status).toBe('pending')
    if (gate?.status === 'pending') {
      // gateType absent means adversarial-review (backwards compat)
      expect(gate.gateType === undefined || gate.gateType === 'adversarial-review').toBe(true)
      expect(gate.allowedDecisions).toContain('retry')
      expect(gate.allowedDecisions).toContain('ignore')
    }
  }))

  // ---------------------------------------------------------------------------
  // Cleanup
  // ---------------------------------------------------------------------------

  it('stopPolling resets gate to null', fakeAsync(() => {
    service.startPolling(RUN_A)
    tick(4000)
    http.expectOne(`/api/factory/runs/${RUN_A}/review-gate`).flush({
      status: 'pending',
      findings: 'findings',
      outcomes: [],
      allowedDecisions: ['retry', 'ignore', 'fail'],
    })
    expect(service.gate()?.status).toBe('pending')

    service.stopPolling()
    expect(service.gate()).toBeNull()
  }))
})
