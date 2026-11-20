import { ActorRoleEnum, CaseEvent, CaseEventSseService, MessageEvent as CaseMessageEvent } from '@whoz-oss/agentos-api-client'
import { TestBed } from '@angular/core/testing'
import { Subject } from 'rxjs'
import { CaseChatWizzStateService, completedAnswerFinishIds } from './case-chat-wizz-state.service'

const metadata = { id: '', created: '', modified: '', removed: false }
const event = (type: string, id: string, fields: object = {}) =>
  ({ id, type, caseId: 'c-1', namespaceId: 'ns-1', timestamp: '', metadata, ...fields }) as CaseEvent
const message = (id: string, agentId: string, content: string) =>
  event('MessageEvent', id, {
    actor: { id: agentId, role: ActorRoleEnum.AGENT },
    content: [{ type: 'Text', content }],
  }) as CaseMessageEvent
const completedTurn = (finishId = 'finish') => [
  event('AgentRunningEvent', 'run', { agentId: 'a-1' }),
  message('answer', 'a-1', 'Done'),
  event('AgentFinishedEvent', finishId, { agentId: 'a-1' }),
]

describe('completedAnswerFinishIds', () => {
  it('maps only a non-empty answer from the same agent to its exact finish id', () => {
    expect(completedAnswerFinishIds(completedTurn())).toEqual(new Set(['finish']))
  })

  it('does not borrow an earlier or another agent answer', () => {
    expect(
      completedAnswerFinishIds([
        message('answer', 'a-1', 'Done'),
        event('AgentSelectedEvent', 'selected', { agentId: 'a-2' }),
        event('AgentFinishedEvent', 'finish', { agentId: 'a-2' }),
      ])
    ).toEqual(new Set())
  })

  it('ignores empty answers and resets at user messages', () => {
    expect(
      completedAnswerFinishIds([
        message('empty', 'a-1', '  '),
        event('AgentFinishedEvent', 'empty-finish', { agentId: 'a-1' }),
        message('answer', 'a-1', 'Done'),
        event('MessageEvent', 'user', {
          actor: { id: 'u-1', role: ActorRoleEnum.USER },
          content: [{ type: 'Text', content: 'Next' }],
        }),
        event('AgentFinishedEvent', 'next-finish', { agentId: 'a-1' }),
      ])
    ).toEqual(new Set())
  })
})

describe('CaseChatWizzStateService', () => {
  let liveEvents: Subject<CaseEvent>
  let sse: { connect: jest.Mock }

  beforeEach(() => {
    liveEvents = new Subject<CaseEvent>()
    sse = { connect: jest.fn(() => liveEvents) }
    TestBed.configureTestingModule({ providers: [CaseChatWizzStateService, { provide: CaseEventSseService, useValue: sse }] })
  })

  afterEach(() => TestBed.resetTestingModule())

  it('correlates a live finish whether the main stream or live stream arrives first, once only', () => {
    const state = TestBed.inject(CaseChatWizzStateService)
    state.toggle('c-1')
    state.setMainEvents(completedTurn())
    liveEvents.next(event('AgentFinishedEvent', 'finish', { agentId: 'a-1' }))
    expect(state.cue()).toBe(1)

    state.reset()
    state.setCase('c-1')
    state.toggle('c-1')
    liveEvents.next(event('AgentFinishedEvent', 'finish', { agentId: 'a-1' }))
    state.setMainEvents(completedTurn())
    liveEvents.next(event('AgentFinishedEvent', 'finish', { agentId: 'a-1' }))
    expect(state.cue()).toBe(2)
  })

  it('closes the live-only subscription on disable and never replays a pending finish', () => {
    const state = TestBed.inject(CaseChatWizzStateService)
    state.toggle('c-1')
    expect(sse.connect).toHaveBeenCalledWith('c-1', { includePreviousEvents: false })
    liveEvents.next(event('AgentFinishedEvent', 'finish', { agentId: 'a-1' }))
    state.disable()
    state.toggle('c-1')
    state.setMainEvents(completedTurn())
    expect(state.cue()).toBe(0)
  })
})
