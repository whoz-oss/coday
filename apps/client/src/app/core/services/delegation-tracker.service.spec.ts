import { TestBed } from '@angular/core/testing'
import { Subject } from 'rxjs'
import { DelegationTrackerService } from './delegation-tracker.service'
import { CodayService } from './coday.service'
import { CodayEvent, DelegationStatusEvent, TextEvent } from '@coday/model'

/** Minimal CodayService mock exposing a controllable subThreadEvents$ subject. */
function makeCodayServiceMock() {
  const subject = new Subject<CodayEvent>()
  return {
    subThreadEvents$: subject.asObservable(),
    _emit: (event: CodayEvent) => subject.next(event),
  }
}

function makeStatusEvent(
  threadId: string,
  status: 'running' | 'completed' | 'failed' | 'interrupted'
): DelegationStatusEvent {
  return new DelegationStatusEvent({ status, threadId })
}

describe('DelegationTrackerService', () => {
  let service: DelegationTrackerService
  let mock: ReturnType<typeof makeCodayServiceMock>

  beforeEach(() => {
    mock = makeCodayServiceMock()
    TestBed.configureTestingModule({
      providers: [DelegationTrackerService, { provide: CodayService, useValue: mock }],
    })
    service = TestBed.inject(DelegationTrackerService)
  })

  afterEach(() => TestBed.resetTestingModule())

  it('should start with runningCount === 0', () => {
    expect(service.runningCount()).toBe(0)
  })

  it('running event → runningCount === 1', () => {
    mock._emit(makeStatusEvent('sub-1', 'running'))
    expect(service.runningCount()).toBe(1)
  })

  it('two running events on different sub-threads → runningCount === 2', () => {
    mock._emit(makeStatusEvent('sub-1', 'running'))
    mock._emit(makeStatusEvent('sub-2', 'running'))
    expect(service.runningCount()).toBe(2)
  })

  it('running then completed on the same threadId → runningCount === 0', () => {
    mock._emit(makeStatusEvent('sub-1', 'running'))
    mock._emit(makeStatusEvent('sub-1', 'completed'))
    expect(service.runningCount()).toBe(0)
  })

  it('failed status decrements the count', () => {
    mock._emit(makeStatusEvent('sub-1', 'running'))
    mock._emit(makeStatusEvent('sub-1', 'failed'))
    expect(service.runningCount()).toBe(0)
  })

  it('interrupted status decrements the count', () => {
    mock._emit(makeStatusEvent('sub-1', 'running'))
    mock._emit(makeStatusEvent('sub-1', 'interrupted'))
    expect(service.runningCount()).toBe(0)
  })

  it('reset() clears the counter', () => {
    mock._emit(makeStatusEvent('sub-1', 'running'))
    mock._emit(makeStatusEvent('sub-2', 'running'))
    service.reset()
    expect(service.runningCount()).toBe(0)
  })

  it('non-DelegationStatusEvent events are ignored', () => {
    mock._emit(new TextEvent({ text: 'hello', threadId: 'sub-1' }))
    expect(service.runningCount()).toBe(0)
  })
})
