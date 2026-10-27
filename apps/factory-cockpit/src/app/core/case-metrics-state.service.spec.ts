import { TestBed, fakeAsync, tick } from '@angular/core/testing'
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing'
import { provideHttpClient } from '@angular/common/http'
import { AgentAttempt } from './models'
import {
  CaseMetricsStateService,
  RunCostDto,
  computeAttemptDuration,
  mapToAttemptMetrics,
} from './case-metrics-state.service'
import { AGENTOS_API_ORIGIN } from './case-event-state.service'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const runCostDto = (overrides: Partial<RunCostDto> = {}): RunCostDto => ({
  caseId: 'case-1',
  since: new Date(Date.now() - 30_000).toISOString(),
  cost: 0.042,
  unknownCostCount: 0,
  active: true,
  liveTokens: 0,
  paused: false,
  ...overrides,
})

const attempt = (overrides: Partial<AgentAttempt> = {}): AgentAttempt => ({
  attemptId: 'att-1',
  stepId: 'plan',
  attemptNumber: 1,
  agentName: 'planner',
  status: 'completed',
  caseId: 'case-1',
  startedAt: '2024-01-01T10:00:00Z',
  completedAt: '2024-01-01T10:01:30Z', // 90 s
  ...overrides,
})

// ---------------------------------------------------------------------------
// computeAttemptDuration — pure function
// ---------------------------------------------------------------------------

describe('computeAttemptDuration', () => {
  it('returns duration in seconds when both timestamps are present', () => {
    const a = attempt({ startedAt: '2024-01-01T10:00:00Z', completedAt: '2024-01-01T10:01:30Z' })
    expect(computeAttemptDuration(a)).toBe(90)
  })

  it('returns null when startedAt is absent', () => {
    const a = attempt({ startedAt: undefined })
    expect(computeAttemptDuration(a)).toBeNull()
  })

  it('returns null when completedAt is absent (attempt still running)', () => {
    const a = attempt({ completedAt: undefined })
    expect(computeAttemptDuration(a)).toBeNull()
  })

  it('returns null for invalid startedAt', () => {
    const a = attempt({ startedAt: 'not-a-date' })
    expect(computeAttemptDuration(a)).toBeNull()
  })

  it('returns null for invalid completedAt', () => {
    const a = attempt({ completedAt: 'not-a-date' })
    expect(computeAttemptDuration(a)).toBeNull()
  })

  it('returns null when completedAt < startedAt (clock skew)', () => {
    const a = attempt({
      startedAt: '2024-01-01T10:01:30Z',
      completedAt: '2024-01-01T10:00:00Z',
    })
    expect(computeAttemptDuration(a)).toBeNull()
  })

  it('rounds to nearest second', () => {
    const a = attempt({
      startedAt: '2024-01-01T10:00:00.000Z',
      completedAt: '2024-01-01T10:00:00.600Z', // 0.6 s → rounds to 1
    })
    expect(computeAttemptDuration(a)).toBe(1)
  })

  it('returns 0 for zero-length attempt (same timestamps)', () => {
    const a = attempt({
      startedAt: '2024-01-01T10:00:00Z',
      completedAt: '2024-01-01T10:00:00Z',
    })
    expect(computeAttemptDuration(a)).toBe(0)
  })
})

// ---------------------------------------------------------------------------
// mapToAttemptMetrics — pure function
// ---------------------------------------------------------------------------

describe('mapToAttemptMetrics', () => {
  describe('cost mapping', () => {
    it('maps runCost from RunCostDto.cost', () => {
      const m = mapToAttemptMetrics('case-1', runCostDto({ cost: 0.05 }), null)
      expect(m.runCost).toBe(0.05)
    })

    it('returns null runCost when run is null (API unavailable)', () => {
      const m = mapToAttemptMetrics('case-1', null, null)
      expect(m.runCost).toBeNull()
    })

    it('sets runCostPartial=true when unknownCostCount > 0', () => {
      const m = mapToAttemptMetrics('case-1', runCostDto({ unknownCostCount: 1 }), null)
      expect(m.runCostPartial).toBe(true)
    })

    it('sets runCostPartial=false when unknownCostCount === 0', () => {
      const m = mapToAttemptMetrics('case-1', runCostDto({ unknownCostCount: 0 }), null)
      expect(m.runCostPartial).toBe(false)
    })
  })

  describe('duration mapping', () => {
    it('computes attemptDurationSec from startedAt / completedAt', () => {
      const a = attempt({ startedAt: '2024-01-01T10:00:00Z', completedAt: '2024-01-01T10:01:30Z' })
      const m = mapToAttemptMetrics('case-1', null, a)
      expect(m.attemptDurationSec).toBe(90)
    })

    it('returns null attemptDurationSec when attempt is null', () => {
      const m = mapToAttemptMetrics('case-1', null, null)
      expect(m.attemptDurationSec).toBeNull()
    })

    it('returns null attemptDurationSec when completedAt absent (still running)', () => {
      const a = attempt({ completedAt: undefined })
      const m = mapToAttemptMetrics('case-1', null, a)
      // Attempt still running: no fabricated elapsed duration
      expect(m.attemptDurationSec).toBeNull()
    })

    it('carries attemptStartedAt and attemptCompletedAt from attempt', () => {
      const a = attempt()
      const m = mapToAttemptMetrics('case-1', null, a)
      expect(m.attemptStartedAt).toBe('2024-01-01T10:00:00Z')
      expect(m.attemptCompletedAt).toBe('2024-01-01T10:01:30Z')
    })

    it('carries null timestamps when attempt is null', () => {
      const m = mapToAttemptMetrics('case-1', null, null)
      expect(m.attemptStartedAt).toBeNull()
      expect(m.attemptCompletedAt).toBeNull()
    })
  })

  describe('scope labels (verified via field presence)', () => {
    it('carries the caseId', () => {
      const m = mapToAttemptMetrics('case-xyz', runCostDto(), attempt())
      expect(m.caseId).toBe('case-xyz')
    })

    it('carries the attemptNumber', () => {
      const a = attempt({ attemptNumber: 3 })
      const m = mapToAttemptMetrics('case-1', null, a)
      expect(m.attemptNumber).toBe(3)
    })

    it('all-null when both run and attempt are null', () => {
      const m = mapToAttemptMetrics('case-1', null, null)
      expect(m.runCost).toBeNull()
      expect(m.runCostPartial).toBe(false)
      expect(m.attemptDurationSec).toBeNull()
      expect(m.attemptStartedAt).toBeNull()
      expect(m.attemptCompletedAt).toBeNull()
      expect(m.attemptNumber).toBe(0)
    })
  })
})

// ---------------------------------------------------------------------------
// CaseMetricsStateService — integration
// ---------------------------------------------------------------------------

describe('CaseMetricsStateService', () => {
  let service: CaseMetricsStateService
  let httpMock: HttpTestingController

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        CaseMetricsStateService,
        provideHttpClient(),
        provideHttpClientTesting(),
        // Override origin to '' so HttpTestingController matches bare paths
        { provide: AGENTOS_API_ORIGIN, useValue: '' },
      ],
    })
    service = TestBed.inject(CaseMetricsStateService)
    httpMock = TestBed.inject(HttpTestingController)
  })

  afterEach(() => {
    // Flush any remaining requests before verify() to handle cases where
    // HttpTestingController keeps a request in the queue after unsubscribe.
    // The token-guard in the service ensures stale responses are discarded.
    httpMock
      .match(() => true)
      .forEach((req) => {
        if (!req.cancelled) req.flush(null)
      })
    httpMock.verify()
  })

  it('starts in idle state with null metrics', () => {
    expect(service.state()).toBe('idle')
    expect(service.metrics()).toBeNull()
    expect(service.isLoading()).toBe(false)
    expect(service.hasMetrics()).toBe(false)
  })

  it('sets loading=true immediately after activate()', () => {
    service.activate('case-1', attempt())
    expect(service.state()).toBe('loading')
    expect(service.isLoading()).toBe(true)
    httpMock.expectOne('/api/cases/case-1/run-cost').flush(runCostDto())
  })

  it('sets metrics and state=ready after a successful fetch', fakeAsync(() => {
    const a = attempt({ startedAt: '2024-01-01T10:00:00Z', completedAt: '2024-01-01T10:01:30Z' })
    service.activate('case-1', a)
    httpMock.expectOne('/api/cases/case-1/run-cost').flush(runCostDto({ cost: 0.042 }))
    tick()
    expect(service.state()).toBe('ready')
    expect(service.hasMetrics()).toBe(true)
    expect(service.metrics()?.runCost).toBe(0.042)
    expect(service.metrics()?.attemptDurationSec).toBe(90)
  }))

  it('sets metrics with null runCost when API returns 500', fakeAsync(() => {
    const a = attempt()
    service.activate('case-1', a)
    httpMock.expectOne('/api/cases/case-1/run-cost').flush('error', { status: 500, statusText: 'Server Error' })
    tick()
    // catchError maps to null — metrics still set (duration from attempt)
    expect(service.state()).toBe('ready')
    expect(service.metrics()?.runCost).toBeNull()
    expect(service.metrics()?.attemptDurationSec).toBe(90)
  }))

  it('shows case-2 metrics after switching before case-1 resolves', fakeAsync(() => {
    // Activate case-1, then immediately switch to case-2 before case-1 resolves.
    // HttpTestingController keeps case-1 in the queue (unsubscribe does not cancel
    // at the mock transport level in jsdom). afterEach flushes stale requests;
    // the token-guard in the service discards the case-1 response.
    service.activate('case-1', attempt({ caseId: 'case-1' }))
    service.activate('case-2', attempt({ caseId: 'case-2' }))

    // Resolve case-2 — the active request.
    httpMock.expectOne('/api/cases/case-2/run-cost').flush(runCostDto({ caseId: 'case-2', cost: 0.99 }))
    tick()

    // Only case-2 metrics must be present.
    // case-1 stale request is cleaned up by afterEach; token-guard discards its response.
    expect(service.state()).toBe('ready')
    expect(service.metrics()?.caseId).toBe('case-2')
    expect(service.metrics()?.runCost).toBe(0.99)
  }))

  it('resets to idle on clear()', fakeAsync(() => {
    service.activate('case-1', attempt())
    httpMock.expectOne('/api/cases/case-1/run-cost').flush(runCostDto())
    tick()
    expect(service.state()).toBe('ready')

    service.clear()
    expect(service.state()).toBe('idle')
    expect(service.metrics()).toBeNull()
    expect(service.isLoading()).toBe(false)
    expect(service.hasMetrics()).toBe(false)
  }))

  it('stays idle after clear() is called before the request resolves', fakeAsync(() => {
    // HttpTestingController keeps the request in the queue after unsubscribe.
    // afterEach flushes it; the token-guard discards the late response.
    service.activate('case-1', attempt())
    expect(service.state()).toBe('loading')

    service.clear()

    // State is immediately idle.
    expect(service.state()).toBe('idle')
    expect(service.metrics()).toBeNull()
    expect(service.isLoading()).toBe(false)
    expect(service.hasMetrics()).toBe(false)
    // The stale request is cleaned up by afterEach; no assertion needed here.
  }))

  it('URL-encodes the caseId in the request path', fakeAsync(() => {
    service.activate('case/with/slashes', attempt())
    httpMock.expectOne('/api/cases/case%2Fwith%2Fslashes/run-cost').flush(runCostDto())
    tick()
    expect(service.state()).toBe('ready')
  }))

  it('computes null attemptDurationSec when attempt has no completedAt', fakeAsync(() => {
    const running = attempt({ completedAt: undefined })
    service.activate('case-1', running)
    httpMock.expectOne('/api/cases/case-1/run-cost').flush(runCostDto())
    tick()
    // Running attempt: no fabricated elapsed duration from now
    expect(service.metrics()?.attemptDurationSec).toBeNull()
    expect(service.metrics()?.attemptCompletedAt).toBeNull()
  }))

  it('sets runCostPartial=true when unknownCostCount > 0', fakeAsync(() => {
    service.activate('case-1', attempt())
    httpMock.expectOne('/api/cases/case-1/run-cost').flush(runCostDto({ unknownCostCount: 2 }))
    tick()
    expect(service.metrics()?.runCostPartial).toBe(true)
  }))
})
