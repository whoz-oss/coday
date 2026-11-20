import { ActorRoleEnum, AgentFinishedEvent, AgentRunningEvent, CaseEvent, MessageEvent } from '@whoz-oss/agentos-api-client'
import { WizzCompletionCorrelator } from './wizz-completion'

const metadata = { id: '', created: '', modified: '', removed: false }
const running = (id = 'running-1', agentId = 'agent-1'): AgentRunningEvent =>
  ({ id, type: 'AgentRunningEvent', caseId: 'case-1', namespaceId: 'ns-1', timestamp: '', metadata, agentId, agentName: 'Agent' }) as AgentRunningEvent
const message = (id: string, role: ActorRoleEnum, actorId: string): MessageEvent =>
  ({
    id,
    type: 'MessageEvent',
    caseId: 'case-1',
    namespaceId: 'ns-1',
    timestamp: '',
    metadata,
    actor: { id: actorId, role, displayName: 'Agent' },
    content: [],
  }) as MessageEvent
const finished = (id = 'finished-1', agentId = 'agent-1'): AgentFinishedEvent =>
  ({ id, type: 'AgentFinishedEvent', caseId: 'case-1', namespaceId: 'ns-1', timestamp: '', metadata, agentId, agentName: 'Agent' })

describe('WizzCompletionCorrelator', () => {
  it('emits once when live and persisted completion arrive in either order', () => {
    const events: CaseEvent[] = [running(), message('answer-1', ActorRoleEnum.AGENT, 'agent-1'), finished()]
    const caseFirst = new WizzCompletionCorrelator('case-1', 'ns-1')
    expect(caseFirst.observeLiveCompletion(finished(), events, true)).toEqual(['finished-1'])
    expect(caseFirst.observeLiveCompletion(finished(), events, true)).toEqual([])

    const globalFirst = new WizzCompletionCorrelator('case-1', 'ns-1')
    expect(globalFirst.observeLiveCompletion(finished(), events.slice(0, 2), true)).toEqual([])
    expect(globalFirst.reconcile(events)).toEqual(['finished-1'])
  })

  it('never turns history, disabled completions, duplicates, or unrelated events into cues', () => {
    const events: CaseEvent[] = [running(), message('answer-1', ActorRoleEnum.AGENT, 'agent-1'), finished()]
    const correlator = new WizzCompletionCorrelator('case-1', 'ns-1')
    expect(correlator.reconcile(events)).toEqual([])
    expect(correlator.observeLiveCompletion(finished(), events, false)).toEqual([])
    expect(correlator.observeLiveCompletion(finished(), events, true)).toEqual([])
    expect(correlator.observeLiveCompletion({ ...finished('other'), caseId: 'case-2' }, events, true)).toEqual([])
    expect(correlator.observeLiveCompletion({ ...finished('other-ns'), namespaceId: 'ns-2' }, events, true)).toEqual([])
  })

  it('requires an answer from the finishing agent and handles handoffs independently', () => {
    const correlator = new WizzCompletionCorrelator('case-1', 'ns-1')
    const handoff: CaseEvent[] = [running('run-a', 'agent-a'), finished('finish-a', 'agent-a'), running('run-b', 'agent-b')]
    expect(correlator.observeLiveCompletion(finished('finish-a', 'agent-a'), handoff, true)).toEqual([])

    const answerEvents = [...handoff, message('answer-b', ActorRoleEnum.AGENT, 'agent-b'), finished('finish-b', 'agent-b')]
    expect(correlator.observeLiveCompletion(finished('finish-b', 'agent-b'), answerEvents, true)).toEqual(['finish-b'])
  })

  it('does not qualify tool-only or another agent’s message, and clears pending candidates', () => {
    const correlator = new WizzCompletionCorrelator('case-1', 'ns-1')
    const noAnswer: CaseEvent[] = [running(), message('other-answer', ActorRoleEnum.AGENT, 'agent-2'), finished()]
    expect(correlator.observeLiveCompletion(finished(), noAnswer, true)).toEqual([])

    const pending = new WizzCompletionCorrelator('case-1', 'ns-1')
    expect(pending.observeLiveCompletion(finished(), [running(), message('answer-1', ActorRoleEnum.AGENT, 'agent-1')], true)).toEqual([])
    pending.clearPending()
    expect(pending.reconcile([running(), message('answer-1', ActorRoleEnum.AGENT, 'agent-1'), finished()])).toEqual([])
  })
})
