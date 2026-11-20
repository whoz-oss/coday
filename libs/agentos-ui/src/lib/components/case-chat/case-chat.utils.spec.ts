import {
  ActorRoleEnum,
  AgentFinishedEvent,
  AgentRunningEvent,
  AgentSelectedEvent,
  AnswerEvent,
  CaseEvent,
  CaseStatusEvent,
  CaseStatusEventStatusEnum,
  MessageEvent as CaseMessageEvent,
  QuestionEvent,
  QuestionEventQuestionTypeEnum,
} from '@whoz-oss/agentos-api-client'
import {
  effectiveConversation,
  findLegitimateAnswer,
  messageAuthorLabel,
  messagingPlatformLabel,
  isAuthorOf,
  rerunErrorMessage,
  runningAfter,
  senderLabel,
  textOfMessage,
  withText,
} from './case-chat.utils'

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
    expect(messagingPlatformLabel({ messaging: { platform: 'SLACK', conversationId: 'c' } })).toBe('Slack')
  })

  it('is null for a message typed in the UI or an unknown platform', () => {
    expect(messagingPlatformLabel(undefined)).toBeNull()
    expect(messagingPlatformLabel({ userContext: {} })).toBeNull()
    expect(messagingPlatformLabel({ messaging: { platform: 'FAX' } })).toBeNull()
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

/** A message of the log: the text of a user message is its id, unless told otherwise. */
function message(
  id: string,
  role: ActorRoleEnum,
  rerunOf?: string,
  content: unknown[] = [{ type: 'Text', content: id }]
): CaseMessageEvent {
  return {
    id,
    type: 'MessageEvent',
    caseId: 'c-1',
    namespaceId: 'ns-1',
    timestamp: '2026-01-01T00:00:00Z',
    metadata,
    actor: { id: role === ActorRoleEnum.USER ? 'u-1' : 'agent-1', role, displayName: id },
    content,
    rerunOf,
  } as unknown as CaseMessageEvent
}

describe('effectiveConversation', () => {
  const user = (id: string, rerunOf?: string) => message(id, ActorRoleEnum.USER, rerunOf)
  const agent = (id: string) => message(id, ActorRoleEnum.AGENT)
  const ids = (events: CaseEvent[]) => events.map((event) => event.id)

  it('returns the very same list when no message re-runs another one', () => {
    const events = [user('u1'), agent('a1'), user('u2')]

    expect(effectiveConversation(events)).toBe(events)
  })

  it('replaces a message and everything recorded after it by its re-run', () => {
    const events = [user('u0'), agent('a0'), user('u1'), agent('a1'), user('u2', 'u1')]

    expect(ids(effectiveConversation(events))).toEqual(['u0', 'a0', 'u2'])
  })

  it('keeps going after the re-run: what follows it is part of the conversation', () => {
    const events = [user('u1'), agent('a1'), user('u2', 'u1'), agent('a2'), user('u3')]

    expect(ids(effectiveConversation(events))).toEqual(['u2', 'a2', 'u3'])
  })

  it('follows a chain of re-runs, a re-run being re-run itself', () => {
    const events = [user('u1'), agent('a1'), user('u2', 'u1'), agent('a2'), user('u3', 'u2'), agent('a3')]

    expect(ids(effectiveConversation(events))).toEqual(['u3', 'a3'])
  })

  it('re-runs the very first message of the case', () => {
    const events = [user('u1'), agent('a1'), user('u2'), agent('a2'), user('u3', 'u1')]

    expect(ids(effectiveConversation(events))).toEqual(['u3'])
  })

  it('cuts the orchestration events, the tools, the questions and the sub-cases that followed the message', () => {
    const running = { ...agentRunning, id: 'run-1' }
    const finished = { ...agentFinished, id: 'fin-1' }
    const status = { ...statusEvent(CaseStatusEventStatusEnum.RUNNING), id: 'status-1' }
    const events = [running, user('u1'), status, question('q1'), agent('a1'), finished, user('u2', 'u1')]

    // What came before the re-run message stays (the first run of the agent), what came after it goes.
    expect(ids(effectiveConversation(events))).toEqual(['run-1', 'u2'])
  })

  it('simply adds a re-run whose target is unknown', () => {
    const events = [user('u1'), agent('a1'), user('u2', 'ghost')]

    expect(ids(effectiveConversation(events))).toEqual(['u1', 'a1', 'u2'])
  })

  it('simply adds a re-run whose target was already cut by an earlier one', () => {
    const events = [user('u1'), agent('a1'), user('u2', 'u1'), user('u3', 'u1')]

    expect(ids(effectiveConversation(events))).toEqual(['u2', 'u3'])
  })

  it('does not take an agent message for a re-run, whatever it carries', () => {
    const events = [user('u1'), agent('a1'), message('a2', ActorRoleEnum.AGENT, 'u1')]

    expect(effectiveConversation(events)).toBe(events)
  })

  it('never modifies the log it reads', () => {
    const events = [user('u1'), agent('a1'), user('u2', 'u1')]

    effectiveConversation(events)

    expect(ids(events)).toEqual(['u1', 'a1', 'u2'])
  })
})

describe('textOfMessage and withText', () => {
  const audio = { type: 'Audio', path: 'voice-messages/v.webm', mimeType: 'audio/webm' }
  const text = (content: string) => ({ type: 'Text', content })

  it('reads the text parts only, in order', () => {
    const event = message('u1', ActorRoleEnum.USER, undefined, [audio, text('hello '), text('there')])

    expect(textOfMessage(event)).toBe('hello there')
  })

  it('puts the new text where the first text part was, drops the other ones, keeps the other parts', () => {
    const event = message('u1', ActorRoleEnum.USER, undefined, [audio, text('one'), text('two')])

    expect(withText(event, 'new').content).toEqual([audio, text('new')])
  })

  it('puts the text first in a message that had none', () => {
    const event = message('u1', ActorRoleEnum.USER, undefined, [audio])

    expect(withText(event, 'new').content).toEqual([text('new'), audio])
  })

  it('leaves the message it starts from untouched', () => {
    const event = message('u1', ActorRoleEnum.USER, undefined, [text('old')])

    withText(event, 'new')

    expect(textOfMessage(event)).toBe('old')
  })
})

describe('isAuthorOf', () => {
  const human = (id: string) => ({ id, role: ActorRoleEnum.USER, displayName: 'x' })

  it('is true for the turn of the current user, whatever the letter case of the ids', () => {
    expect(isAuthorOf(human('u-1'), 'u-1')).toBe(true)
    expect(isAuthorOf(human('ABCDEF00-0000-0000-0000-000000000001'), 'abcdef00-0000-0000-0000-000000000001')).toBe(true)
  })

  it('is false for someone else, for an agent and while the current user is not loaded', () => {
    expect(isAuthorOf(human('u-2'), 'u-1')).toBe(false)
    expect(isAuthorOf({ id: 'u-1', role: ActorRoleEnum.AGENT, displayName: 'x' }, 'u-1')).toBe(false)
    expect(isAuthorOf(human('u-1'), null)).toBe(false)
    expect(isAuthorOf(human('u-1'), undefined)).toBe(false)
  })
})

describe('rerunErrorMessage', () => {
  it('says why the server refused, by status', () => {
    expect(rerunErrorMessage(400)).toContain('cannot be re-run')
    expect(rerunErrorMessage(403)).toContain('author')
    expect(rerunErrorMessage(409)).toContain('run is in progress')
  })

  it('falls back to a generic message for any other failure', () => {
    expect(rerunErrorMessage(500)).toContain('could not be sent')
    expect(rerunErrorMessage(undefined)).toContain('could not be sent')
  })
})
