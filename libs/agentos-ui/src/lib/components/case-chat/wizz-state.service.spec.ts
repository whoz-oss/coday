import { TestBed } from '@angular/core/testing'
import {
  ActorRoleEnum,
  AgentFinishedEvent,
  AgentRunningEvent,
  CaseEvent,
  MessageEvent,
} from '@whoz-oss/agentos-api-client'
import { Subject } from 'rxjs'
import { GlobalStreamService } from '../../services/global-stream.service'
import { WizzStateService } from './wizz-state.service'

describe('WizzStateService', () => {
  const globalEvents = new Subject<CaseEvent>()
  const connected = new Subject<void>()
  const metadata = { id: '', created: '', modified: '', removed: false }
  let service: WizzStateService

  const running = (id = 'running'): AgentRunningEvent =>
    ({
      id,
      type: 'AgentRunningEvent',
      caseId: 'case-1',
      namespaceId: 'ns-1',
      timestamp: '',
      metadata,
      agentId: 'agent-1',
      agentName: 'Agent',
    }) as AgentRunningEvent
  const answer = (id = 'answer', text = 'Done'): MessageEvent =>
    ({
      id,
      type: 'MessageEvent',
      caseId: 'case-1',
      namespaceId: 'ns-1',
      timestamp: '',
      metadata,
      actor: { id: 'agent-1', role: ActorRoleEnum.AGENT },
      content: [{ type: 'Text', content: text }],
    }) as MessageEvent
  const finish = (id = 'finish'): AgentFinishedEvent =>
    ({
      id,
      type: 'AgentFinishedEvent',
      caseId: 'case-1',
      namespaceId: 'ns-1',
      timestamp: '',
      metadata,
      agentId: 'agent-1',
      agentName: 'Agent',
    }) as AgentFinishedEvent

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        WizzStateService,
        { provide: GlobalStreamService, useValue: { caseEvents$: globalEvents, connected$: connected } },
      ],
    })
    service = TestBed.inject(WizzStateService)
    service.setContext('case-1', 'ns-1')
  })

  afterEach(() => TestBed.resetTestingModule())

  it('pulses once when the live finish and visible answer arrive in either transport order', () => {
    const pulses = jest.fn()
    service.completed$.subscribe(pulses)
    service.toggle()
    const events = [running(), answer(), finish()]

    globalEvents.next(events[2]!)
    service.acceptTimeline(events)
    globalEvents.next(events[2]!)

    expect(pulses).toHaveBeenCalledTimes(1)
  })

  it('waits for the local finish when the live completion arrives after the answer', () => {
    const pulses = jest.fn()
    service.completed$.subscribe(pulses)
    service.toggle()
    const events = [running(), answer(), finish()]

    service.acceptTimeline(events.slice(0, 2))
    globalEvents.next(events[2]!)
    expect(pulses).not.toHaveBeenCalled()
    service.acceptTimeline(events)

    expect(pulses).toHaveBeenCalledTimes(1)
  })

  it('ignores history, empty runs, other contexts and pending work after disable', () => {
    const pulses = jest.fn()
    service.completed$.subscribe(pulses)
    const events = [running(), answer(), finish()]
    service.acceptTimeline(events)
    service.toggle()
    expect(pulses).not.toHaveBeenCalled()

    globalEvents.next({ ...finish('other'), caseId: 'case-2' })
    globalEvents.next({ ...finish('empty'), agentId: 'agent-2' })
    service.toggle()
    globalEvents.next(events[2]!)
    service.toggle()
    service.acceptTimeline(events)

    expect(pulses).not.toHaveBeenCalled()
  })

  it('clears enabled state and correlation on a context switch', () => {
    const pulses = jest.fn()
    service.completed$.subscribe(pulses)
    service.toggle()
    const events = [running(), answer(), finish()]
    globalEvents.next(events[2]!)
    service.setContext('case-2', 'ns-1')
    service.acceptTimeline(events)

    expect(service.enabled()).toBe(false)
    expect(pulses).not.toHaveBeenCalled()
  })
})
