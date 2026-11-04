import { ActorRoleEnum, AnswerEvent, QuestionEvent, QuestionEventQuestionTypeEnum } from '@whoz-oss/agentos-api-client'
import { findLegitimateAnswer, messageAuthorLabel, messagingPlatformLabel } from './case-chat.utils'

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

describe('messageAuthorLabel', () => {
  const ada = { id: 'ADA-1', role: ActorRoleEnum.USER, displayName: 'Ada Lovelace' }

  it('names the current user "You", whatever the case of the id', () => {
    expect(messageAuthorLabel(ada, 'ada-1')).toBe('You')
  })

  it('names another user by their name', () => {
    expect(messageAuthorLabel(ada, 'bob-2')).toBe('Ada Lovelace')
  })

  it('keeps "You" for every user message while the current user is unknown', () => {
    expect(messageAuthorLabel(ada, undefined)).toBe('You')
  })

  it('names an agent by its name', () => {
    expect(messageAuthorLabel({ id: 'a', role: ActorRoleEnum.AGENT, displayName: 'Coday' }, 'ada-1')).toBe('Coday')
  })
})

describe('messagingPlatformLabel', () => {
  it('names the application a message came from', () => {
    expect(messagingPlatformLabel({ messaging: { platform: 'TELEGRAM', conversationId: 'c' } })).toBe('Telegram')
  })

  it('is null for a message typed in the UI or an unknown platform', () => {
    expect(messagingPlatformLabel(undefined)).toBeNull()
    expect(messagingPlatformLabel({ userContext: {} })).toBeNull()
    expect(messagingPlatformLabel({ messaging: { platform: 'FAX' } })).toBeNull()
  })
})
