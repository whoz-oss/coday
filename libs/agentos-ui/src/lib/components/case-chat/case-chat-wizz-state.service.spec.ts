import { TestBed } from '@angular/core/testing'
import { CaseEvent, CaseEventSseService } from '@whoz-oss/agentos-api-client'
import { Subject } from 'rxjs'
import { CaseChatWizzStateService } from './case-chat-wizz-state.service'

const metadata = { id: '', created: '', modified: '', removed: false }
const agentMessage = (id = 'message-1', text = 'Done', agentId = 'agent-1') =>
  ({ id, type: 'MessageEvent', caseId: 'case-1', namespaceId: 'ns-1', timestamp: '', metadata, actor: { id: agentId, role: 'AGENT' }, content: [{ type: 'Text', content: text }] }) as never
const finished = (id = 'finish-1', agentId = 'agent-1') =>
  ({ id, type: 'AgentFinishedEvent', caseId: 'case-1', namespaceId: 'ns-1', timestamp: '', metadata, agentId, agentName: 'Agent' }) as never

describe('CaseChatWizzStateService', () => {
  let liveEvents: Subject<CaseEvent>
  let connect: jest.Mock
  let service: CaseChatWizzStateService

  beforeEach(() => {
    liveEvents = new Subject<CaseEvent>()
    connect = jest.fn(() => liveEvents)
    TestBed.configureTestingModule({ providers: [CaseChatWizzStateService, { provide: CaseEventSseService, useValue: { connect } }] })
    service = TestBed.inject(CaseChatWizzStateService)
  })

  it('is off by default and opens exactly one live-only connection only while enabled', () => {
    expect(service.enabled()).toBe(false)
    expect(connect).not.toHaveBeenCalled()
    service.enable('case-1')
    expect(connect).toHaveBeenCalledWith('case-1', { includePreviousEvents: false })
    service.disable()
    expect(service.enabled()).toBe(false)
  })

  it('cues exactly once when live and displayed completion arrive in either order', () => {
    service.enable('case-1')
    liveEvents.next(finished())
    expect(service.cueSequence()).toBe(0)
    service.reconcile([agentMessage(), finished()])
    expect(service.cueSequence()).toBe(1)
    liveEvents.next(finished())
    expect(service.cueSequence()).toBe(1)

    service.reset()
    service.enable('case-1')
    service.reconcile([agentMessage('message-2'), finished('finish-2')])
    liveEvents.next(finished('finish-2'))
    expect(service.cueSequence()).toBe(2)
  })

  it('never treats history, an empty/wrong agent answer, or an earlier run answer as a completion', () => {
    service.reconcile([agentMessage(), finished()])
    expect(service.cueSequence()).toBe(0)
    service.enable('case-1')
    liveEvents.next(finished('empty'))
    service.reconcile([agentMessage('empty-message', ''), finished('empty')])
    liveEvents.next(finished('wrong-agent', 'agent-2'))
    service.reconcile([agentMessage('wrong-message', 'Done', 'agent-1'), finished('wrong-agent', 'agent-2')])
    liveEvents.next(finished('new-run'))
    service.reconcile([agentMessage('old'), { id: 'running', type: 'AgentRunningEvent', caseId: 'case-1' } as never, finished('new-run')])
    expect(service.cueSequence()).toBe(0)
  })

  it('clears pending provenance on retry and reset, ignoring stale callbacks', () => {
    service.enable('case-1')
    const oldStream = liveEvents
    oldStream.next(finished())
    service.retry('case-1')
    service.reconcile([agentMessage(), finished()])
    expect(service.cueSequence()).toBe(0)
    oldStream.next(finished())
    expect(service.cueSequence()).toBe(0)
    service.reset()
    expect(service.enabled()).toBe(false)
  })
})
