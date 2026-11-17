import {
  ActorRoleEnum,
  AgentFinishedEvent,
  AgentRunningEvent,
  AgentSelectedEvent,
  AnswerEvent,
  CaseStatusEvent,
  CaseStatusEventStatusEnum,
  QuestionEvent,
  QuestionEventQuestionTypeEnum,
} from '@whoz-oss/agentos-api-client'
import { findLegitimateAnswer, runningAfter, senderLabel } from './case-chat.utils'

const metadata = { id: '', created: '', modified: '', removed: false }

function question(id: string, userId?: string): QuestionEvent {
  return {
    id,
    type: 'QuestionEvent',
    caseId: 'c-1',
    namespaceId: 'ns-1',
    timestamp: '2026-01-01T00:00:00Z',
    metadata,
    agentId: 'agent-1',
    agentName: 'Agent',
    question: 'Which color?',
    questionType: QuestionEventQuestionTypeEnum.FREE_TEXT,
    userId,
  }
}

function answer(id: string, questionId: string, actorId: string, text: string): AnswerEvent {
  return {
    id,
    type: 'AnswerEvent',
    caseId: 'c-1',
    namespaceId: 'ns-1',
    timestamp: '2026-01-01T00:00:01Z',
    metadata,
    questionId,
    answer: text,
    actor: { id: actorId, role: ActorRoleEnum.USER, displayName: actorId },
  }
}

describe('findLegitimateAnswer', () => {
  it('returns undefined when there is no answer', () => {
    expect(findLegitimateAnswer(question('q1'), [question('q1')])).toBeUndefined()
  })

  it('ignores answers paired with another question', () => {
    const events = [question('q1'), answer('a1', 'q2', 'alice', 'red')]
    expect(findLegitimateAnswer(question('q1'), events)).toBeUndefined()
  })

  it('accepts any respondent when the question is unaddressed', () => {
    const a = answer('a1', 'q1', 'bob', 'red')
    expect(findLegitimateAnswer(question('q1'), [question('q1'), a])).toBe(a)
  })

  it('picks the first answer in event order for an unaddressed question', () => {
    const first = answer('a1', 'q1', 'bob', 'red')
    const second = answer('a2', 'q1', 'alice', 'blue')
    expect(findLegitimateAnswer(question('q1'), [question('q1'), first, second])).toBe(first)
  })

  it('rejects an answer from someone else than the addressed user', () => {
    const events = [question('q1', 'alice'), answer('a1', 'q1', 'bob', 'red')]
    expect(findLegitimateAnswer(question('q1', 'alice'), events)).toBeUndefined()
  })

  it('skips a wrong respondent answer and picks the addressed user one, even if later', () => {
    const wrong = answer('a1', 'q1', 'bob', 'red')
    const legit = answer('a2', 'q1', 'alice', 'blue')
    expect(findLegitimateAnswer(question('q1', 'alice'), [question('q1', 'alice'), wrong, legit])).toBe(legit)
  })

  it('compares user ids regardless of the UUID letter case', () => {
    const a = answer('a1', 'q1', 'ABCDEF00-0000-0000-0000-000000000001', 'red')
    const q = question('q1', 'abcdef00-0000-0000-0000-000000000001')
    expect(findLegitimateAnswer(q, [q, a])).toBe(a)
  })
})

describe('senderLabel', () => {
  const human = (id: string, displayName?: string) =>
    ({ id, role: ActorRoleEnum.USER, displayName }) as Parameters<typeof senderLabel>[0]
  const agent = (displayName?: string) =>
    ({ id: 'agent-1', role: ActorRoleEnum.AGENT, displayName }) as Parameters<typeof senderLabel>[0]

  it('says You for a turn of the current user', () => {
    expect(senderLabel(human('u-1', 'Alice'), 'u-1')).toBe('You')
  })

  it('names another user, so a collaborator is not taken for the reader', () => {
    expect(senderLabel(human('u-2', 'Bob'), 'u-1')).toBe('Bob')
  })

  it('falls back to You for another user without a name (legacy events)', () => {
    expect(senderLabel(human('u-2'), 'u-1')).toBe('You')
  })

  it('names the sender while the current user is not loaded yet', () => {
    expect(senderLabel(human('u-2', 'Bob'), null)).toBe('Bob')
    expect(senderLabel(human('u-2', 'Bob'), undefined)).toBe('Bob')
  })

  it('falls back to You without a name while the current user is not loaded yet', () => {
    expect(senderLabel(human('u-2'), null)).toBe('You')
  })

  it('compares user ids regardless of the UUID letter case', () => {
    expect(
      senderLabel(human('ABCDEF00-0000-0000-0000-000000000001', 'Alice'), 'abcdef00-0000-0000-0000-000000000001')
    ).toBe('You')
  })

  it('names an agent, or says Agent when it has no name', () => {
    expect(senderLabel(agent('Dev'), 'u-1')).toBe('Dev')
    expect(senderLabel(agent(), 'u-1')).toBe('Agent')
  })
})

const agentBase = {
  id: 'e1',
  caseId: 'c-1',
  namespaceId: 'ns-1',
  timestamp: '2026-01-01T00:00:00Z',
  metadata,
  agentId: 'agent-1',
  agentName: 'Agent',
}
const agentRunning: AgentRunningEvent = { ...agentBase, type: 'AgentRunningEvent' }
const agentSelected: AgentSelectedEvent = { ...agentBase, type: 'AgentSelectedEvent' }
const agentFinished: AgentFinishedEvent = { ...agentBase, type: 'AgentFinishedEvent' }

function statusEvent(value: CaseStatusEventStatusEnum): CaseStatusEvent {
  return {
    id: 's1',
    type: 'CaseStatusEvent',
    caseId: 'c-1',
    namespaceId: 'ns-1',
    timestamp: '2026-01-01T00:00:00Z',
    metadata,
    status: value,
  }
}

describe('runningAfter', () => {
  it('reads a RUNNING status as running', () => {
    expect(runningAfter(statusEvent(CaseStatusEventStatusEnum.RUNNING))).toBe(true)
  })

  it('reads a PENDING status as running, because the gate holds a turn it will admit', () => {
    expect(runningAfter(statusEvent(CaseStatusEventStatusEnum.PENDING))).toBe(true)
  })

  it.each([
    CaseStatusEventStatusEnum.CREATED,
    CaseStatusEventStatusEnum.IDLE,
    CaseStatusEventStatusEnum.KILLED,
    CaseStatusEventStatusEnum.ERROR,
  ])('reads a %s status as not running', (status) => {
    expect(runningAfter(statusEvent(status))).toBe(false)
  })

  it('does not decide on a status it does not know, rather than unlocking the composer', () => {
    // Frames are parsed without validation, so a newer server can send a status this UI has never
    // heard of. Returning null leaves the previous telling event standing.
    const unknown = statusEvent('SUSPENDED' as CaseStatusEventStatusEnum)
    expect(runningAfter(unknown)).toBeNull()
  })

  it('reads an agent starting as running', () => {
    expect(runningAfter(agentRunning)).toBe(true)
  })

  it('reads an agent being selected as running, so a handoff does not read as an end of turn', () => {
    // A redirect, a command-queue drain and a question resumption all emit AgentFinishedEvent,
    // AgentSelectedEvent, AgentRunningEvent without the case changing status. The selection is what
    // tells the turn is still open in between, as it does for the backend's own reader.
    expect(runningAfter(agentSelected)).toBe(true)
  })

  it('reads an agent finishing as not running', () => {
    expect(runningAfter(agentFinished)).toBe(false)
  })

  it('does not decide on an event that says nothing about the run', () => {
    expect(runningAfter(question('q1'))).toBeNull()
  })
})
