import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing'
import { of, Subject, throwError } from 'rxjs'
import { FactoryApiService, FactoryRunDetail, FactoryRunSummary } from './factory-api.service'
import { FactoryStateService } from './factory-state.service'

const BASE_DETAIL: FactoryRunDetail = {
  runId: 'run-1',
  namespaceId: 'ns',
  workflow: 'fix-loop',
  status: 'pass',
  startedAt: null,
  endedAt: null,
  durationMs: null,
  phaseCount: 0,
  phases: [],
}

const RUNNING_DETAIL: FactoryRunDetail = { ...BASE_DETAIL, runId: 'run-run', status: 'running' }

const BASE_SUMMARY: FactoryRunSummary = {
  runId: 'run-1',
  workflow: 'fix-loop',
  status: 'pass',
  startedAt: null,
  endedAt: null,
  durationMs: null,
  phaseCount: 0,
}

const RUNNING_SUMMARY: FactoryRunSummary = { ...BASE_SUMMARY, runId: 'run-run', status: 'running' }

interface ApiMock {
  listRuns: jest.Mock
  getRun: jest.Mock
  streamRun: jest.Mock
}

function createService(): { service: FactoryStateService; api: ApiMock } {
  const api: ApiMock = {
    listRuns: jest.fn().mockReturnValue(of([])),
    getRun: jest.fn().mockReturnValue(of(BASE_DETAIL)),
    streamRun: jest.fn().mockReturnValue(new Subject()),
  }
  TestBed.configureTestingModule({
    providers: [FactoryStateService, { provide: FactoryApiService, useValue: api }],
  })
  return { service: TestBed.inject(FactoryStateService), api }
}

describe('FactoryStateService', () => {
  afterEach(() => TestBed.resetTestingModule())

  // ── Existing list / detail tests (preserved) ─────────────────────────────────────────

  it('loads only the requested namespace into reactive state', () => {
    const { service, api } = createService()
    const runs: FactoryRunSummary[] = [
      {
        runId: 'run-1',
        workflow: 'fix-loop',
        status: 'pass',
        startedAt: null,
        endedAt: null,
        durationMs: null,
        phaseCount: 1,
      },
    ]
    api.listRuns.mockReturnValue(of(runs))

    service.load('namespace-1')

    expect(api.listRuns).toHaveBeenCalledWith('namespace-1')
    expect(service.namespaceId()).toBe('namespace-1')
    expect(service.runs()).toEqual(runs)
    expect(service.loading()).toBe(false)
  })

  it('exposes a user-facing error without retaining stale runs', () => {
    const { service, api } = createService()
    api.listRuns.mockReturnValue(throwError(() => new Error('unavailable')))

    service.load('namespace-1')

    expect(service.runs()).toEqual([])
    expect(service.loading()).toBe(false)
    expect(service.error()).toBe('Factory runs could not be loaded. Please try again.')
  })

  it('rejects a detail run from another namespace', () => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of({ ...BASE_DETAIL, runId: 'other', namespaceId: 'other-ns' }))

    service.loadDetail('other', 'selected-ns')

    expect(service.namespaceMismatch()).toBe(true)
    expect(service.selectedRun()).toBeNull()
  })

  it('ignores stale detail responses', () => {
    const { service, api } = createService()
    const first = new Subject<FactoryRunDetail>()
    const second = new Subject<FactoryRunDetail>()
    api.getRun.mockReturnValueOnce(first).mockReturnValueOnce(second)

    service.loadDetail('first', 'ns')
    service.loadDetail('second', 'ns')
    first.next({ ...BASE_DETAIL, runId: 'first' })
    second.next({ ...BASE_DETAIL, runId: 'second' })

    expect(service.selectedRun()?.runId).toBe('second')
  })

  it('refreshes the current namespace only', () => {
    const { service, api } = createService()
    service.load('namespace-1')
    api.listRuns.mockClear()

    service.refresh()

    expect(api.listRuns).toHaveBeenCalledWith('namespace-1')
  })

  // ── 4-second detail polling tests ──────────────────────────────────────────────────

  it('starts polling after loadDetail for a running run', fakeAsync(() => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of(RUNNING_DETAIL))
    api.streamRun.mockReturnValue(new Subject())

    service.loadDetail('run-run', 'ns')
    // Initial loadDetail call consumed one getRun call.
    api.getRun.mockClear()

    tick(4000)

    expect(api.getRun).toHaveBeenCalledWith('run-run')
    discardPeriodicTasks()
  }))

  it('does not poll for a non-running run', fakeAsync(() => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of(BASE_DETAIL))

    service.loadDetail('run-1', 'ns')
    api.getRun.mockClear()

    tick(8000)

    expect(api.getRun).not.toHaveBeenCalled()
  }))

  it('polls approximately every 4 seconds', fakeAsync(() => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of(RUNNING_DETAIL))
    api.streamRun.mockReturnValue(new Subject())

    service.loadDetail('run-run', 'ns')
    api.getRun.mockClear()

    tick(4000)
    expect(api.getRun).toHaveBeenCalledTimes(1)
    tick(4000)
    expect(api.getRun).toHaveBeenCalledTimes(2)
    tick(4000)
    expect(api.getRun).toHaveBeenCalledTimes(3)
    discardPeriodicTasks()
  }))

  it('stops polling when run transitions to a terminal status', fakeAsync(() => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of(RUNNING_DETAIL))
    api.streamRun.mockReturnValue(new Subject())
    service.loadDetail('run-run', 'ns')
    api.getRun.mockClear()

    // First poll returns completed run
    api.getRun.mockReturnValue(of({ ...BASE_DETAIL, runId: 'run-run', status: 'pass' }))
    tick(4000)
    api.getRun.mockClear()

    // No more polls after terminal
    tick(8000)
    expect(api.getRun).not.toHaveBeenCalled()
  }))

  it('stops polling after clearDetail', fakeAsync(() => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of(RUNNING_DETAIL))
    api.streamRun.mockReturnValue(new Subject())
    service.loadDetail('run-run', 'ns')
    api.getRun.mockClear()

    service.clearDetail()
    tick(8000)

    expect(api.getRun).not.toHaveBeenCalled()
  }))

  it('stops polling after clear', fakeAsync(() => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of(RUNNING_DETAIL))
    api.streamRun.mockReturnValue(new Subject())
    service.loadDetail('run-run', 'ns')
    api.getRun.mockClear()

    service.clear()
    tick(8000)

    expect(api.getRun).not.toHaveBeenCalled()
  }))

  it('stops polling on ngOnDestroy', fakeAsync(() => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of(RUNNING_DETAIL))
    api.streamRun.mockReturnValue(new Subject())
    service.loadDetail('run-run', 'ns')
    api.getRun.mockClear()

    service.ngOnDestroy()
    tick(8000)

    expect(api.getRun).not.toHaveBeenCalled()
  }))

  it('does not create a duplicate poll when startPoll is called twice for the same run', fakeAsync(() => {
    const { service, api } = createService()
    api.getRun.mockReturnValue(of(RUNNING_DETAIL))
    api.streamRun.mockReturnValue(new Subject())

    // First loadDetail starts polling.
    service.loadDetail('run-run', 'ns')
    api.getRun.mockClear()

    // Simulate SSE triggering a refreshDetailQuiet that finds the run still running:
    // loadDetail is called again for the same run — previously the only codepath
    // through startStream/startPoll.
    // The guard in startPoll should block the second instance.
    service.loadDetail('run-run', 'ns')
    api.getRun.mockClear()

    tick(4000)
    // Only one poll fired, not two.
    expect(api.getRun).toHaveBeenCalledTimes(1)
    discardPeriodicTasks()
  }))

  it('discards stale poll responses after switching to a different run', fakeAsync(() => {
    const { service, api } = createService()
    const slowSubject = new Subject<FactoryRunDetail>()
    const fast = new Subject<FactoryRunDetail>()

    api.getRun
      .mockReturnValueOnce(of(RUNNING_DETAIL)) // first loadDetail fast
      .mockReturnValue(slowSubject) // first poll — delayed
    api.streamRun.mockReturnValue(new Subject())

    service.loadDetail('run-run', 'ns')
    tick(4000) // triggers first poll, subscribed to slowSubject

    // Switch to a new run before the first poll resolves.
    api.getRun.mockReturnValueOnce(fast)
    service.loadDetail('run-2', 'ns')
    fast.next({ ...BASE_DETAIL, runId: 'run-2', status: 'pass' })

    // Now the stale slow response arrives — must be discarded.
    slowSubject.next({ ...RUNNING_DETAIL, status: 'running' })

    expect(service.selectedRun()?.runId).toBe('run-2')
    discardPeriodicTasks()
  }))

  it('stops polling when stream errors', fakeAsync(() => {
    const { service, api } = createService()
    const streamSubject = new Subject<MessageEvent>()
    api.getRun.mockReturnValue(of(RUNNING_DETAIL))
    api.streamRun.mockReturnValue(streamSubject)

    service.loadDetail('run-run', 'ns')
    api.getRun.mockClear()

    // SSE stream errors out
    streamSubject.error(new Error('disconnected'))
    tick(8000)

    expect(api.getRun).not.toHaveBeenCalled()
  }))

  // ── In-place list update from detail ────────────────────────────────────────────────

  describe('in-place list projection from detail', () => {
    it('updates matching list entry when detail is loaded', () => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns')

      // Detail arrives with updated fields
      const updatedDetail: FactoryRunDetail = {
        ...RUNNING_DETAIL,
        runId: 'run-run',
        status: 'pass',
        durationMs: 1200,
        phaseCount: 3,
      }
      api.getRun.mockReturnValue(of(updatedDetail))
      api.streamRun.mockReturnValue(new Subject())
      service.loadDetail('run-run', 'ns')

      const entry = service.runs().find((r) => r.runId === 'run-run')
      expect(entry?.status).toBe('pass')
      expect(entry?.durationMs).toBe(1200)
      expect(entry?.phaseCount).toBe(3)
    })

    it('does not modify the list when runId is not in the list', () => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([BASE_SUMMARY]))
      service.load('ns')

      const brandNew: FactoryRunDetail = { ...BASE_DETAIL, runId: 'brand-new', namespaceId: 'ns' }
      api.getRun.mockReturnValue(of(brandNew))
      service.loadDetail('brand-new', 'ns')

      // 'brand-new' is not in the list — list unchanged
      expect(service.runs().map((r) => r.runId)).toEqual(['run-1'])
    })

    it('projects detail into list on every 4-second poll tick', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns')

      api.getRun.mockReturnValue(of(RUNNING_DETAIL))
      api.streamRun.mockReturnValue(new Subject())
      service.loadDetail('run-run', 'ns')
      api.getRun.mockClear()

      const polledDetail: FactoryRunDetail = {
        ...RUNNING_DETAIL,
        durationMs: 5000,
        phaseCount: 2,
      }
      api.getRun.mockReturnValue(of(polledDetail))
      tick(4000)

      const entry = service.runs().find((r) => r.runId === 'run-run')
      expect(entry?.durationMs).toBe(5000)
      expect(entry?.phaseCount).toBe(2)
      discardPeriodicTasks()
    }))
  })

  // ── List-level polling (10 s) ────────────────────────────────────────────────────────

  describe('list-level polling', () => {
    it('does not start list polling when no runs are running', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([BASE_SUMMARY]))
      service.load('ns')
      api.listRuns.mockClear()

      tick(10_000)
      expect(api.listRuns).not.toHaveBeenCalled()
    }))

    it('starts list polling when at least one run is running', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns')
      api.listRuns.mockClear()

      tick(10_000)
      expect(api.listRuns).toHaveBeenCalledWith('ns')
      discardPeriodicTasks()
    }))

    it('polls approximately every 10 seconds', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns')
      api.listRuns.mockClear()

      tick(10_000)
      expect(api.listRuns).toHaveBeenCalledTimes(1)
      tick(10_000)
      expect(api.listRuns).toHaveBeenCalledTimes(2)
      discardPeriodicTasks()
    }))

    it('stops list polling when no running runs remain', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns')
      api.listRuns.mockClear()

      // First poll returns all terminal runs
      api.listRuns.mockReturnValue(of([BASE_SUMMARY]))
      tick(10_000)
      api.listRuns.mockClear()

      // No more polls
      tick(20_000)
      expect(api.listRuns).not.toHaveBeenCalled()
    }))

    it('does not create duplicate list polls when load() is called twice rapidly', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))

      service.load('ns')
      service.load('ns') // second call — should cancel the first subscription, restart once
      api.listRuns.mockClear()

      tick(10_000)
      // Only one list poll tick, not two
      expect(api.listRuns).toHaveBeenCalledTimes(1)
      discardPeriodicTasks()
    }))

    it('stops list polling on namespace change (stale response guard)', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns-1')

      // Switch to different namespace before poll fires
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns-2')
      api.listRuns.mockClear()

      tick(10_000)
      // The fired poll is for 'ns-2', not 'ns-1'
      expect(api.listRuns).toHaveBeenCalledWith('ns-2')
      discardPeriodicTasks()
    }))

    it('stops list polling after clear()', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns')
      api.listRuns.mockClear()

      service.clear()
      tick(20_000)
      expect(api.listRuns).not.toHaveBeenCalled()
    }))

    it('stops list polling on ngOnDestroy', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns')
      api.listRuns.mockClear()

      service.ngOnDestroy()
      tick(20_000)
      expect(api.listRuns).not.toHaveBeenCalled()
    }))

    it('merges new runs from the list poll without losing existing entries', fakeAsync(() => {
      const { service, api } = createService()
      const initialRun: FactoryRunSummary = { ...RUNNING_SUMMARY, runId: 'run-a' }
      api.listRuns.mockReturnValue(of([initialRun]))
      service.load('ns')
      api.listRuns.mockClear()

      const updatedA: FactoryRunSummary = { ...initialRun, status: 'pass' }
      const brandNew: FactoryRunSummary = { ...BASE_SUMMARY, runId: 'run-b' }
      api.listRuns.mockReturnValue(of([updatedA, brandNew]))
      tick(10_000)

      const ids = service.runs().map((r) => r.runId)
      expect(ids).toContain('run-a')
      expect(ids).toContain('run-b')
      const a = service.runs().find((r) => r.runId === 'run-a')
      expect(a?.status).toBe('pass')
      discardPeriodicTasks()
    }))

    it('does not overlap list poll requests (switchMap cancels in-flight)', fakeAsync(() => {
      const { service, api } = createService()
      api.listRuns.mockReturnValue(of([RUNNING_SUMMARY]))
      service.load('ns')

      // Provide a slow response for the first poll
      const slowResponse = new Subject<FactoryRunSummary[]>()
      api.listRuns.mockReturnValue(slowResponse)

      tick(10_000) // first poll fires, waiting for slowResponse

      // Before slowResponse resolves, second tick fires and gets a fast response
      const fastResponse = of([RUNNING_SUMMARY])
      api.listRuns.mockReturnValue(fastResponse)
      tick(10_000)

      // Emitting on slowResponse should be ignored (cancelled by switchMap)
      const consoleSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined)
      slowResponse.next([{ ...RUNNING_SUMMARY, status: 'crashed' }])

      // The 'crashed' status from the stale response must NOT appear
      expect(service.runs().find((r) => r.runId === 'run-run')?.status).toBe('running')
      consoleSpy.mockRestore()
      discardPeriodicTasks()
    }))
  })
})
