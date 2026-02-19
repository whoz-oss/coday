import { MessageEvent } from '@coday/model'
import { AnswerEvent, SummaryEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/model'
import { AiThread } from '@coday/model'
import { OpenaiClient } from './openai.client'

/**
 * Tests for the multi-agent message conversion feature (issue #485).
 *
 * When multiple agents communicate in a thread, messages from other agents
 * (not the current one) must be converted from 'assistant' role to 'user' role
 * with XML tags: <agent=AgentName>content</agent>
 *
 * This ensures LLMs can distinguish between:
 * - The current agent's own messages (role: assistant)
 * - Other agents' contributions (role: user, wrapped in XML tags)
 */

// Access the protected method via a test subclass
class TestableAiClient extends OpenaiClient {
  callConvertAgentMessages(messages: any[], agentName: string): any[] {
    return this.convertAgentMessages(messages, agentName)
  }
}

function makeMessageEvent(role: 'user' | 'assistant', name: string, text: string): MessageEvent {
  return new MessageEvent({
    role,
    name,
    content: [{ type: 'text', content: text }],
  })
}

function makeTestClient(): TestableAiClient {
  const interactor: any = {
    warn: jest.fn(),
    displayText: jest.fn(),
    debug: jest.fn(),
    thinking: jest.fn(),
    sendEvent: jest.fn(),
    chooseOption: jest.fn(),
    promptText: jest.fn(),
    error: jest.fn(),
  }
  const config: any = {
    name: 'openai',
    apiKey: 'test-key',
    models: [],
  }
  const logger: any = { logAgentUsage: jest.fn() }
  return new TestableAiClient(interactor, config, logger)
}

describe('AiClient.convertAgentMessages', () => {
  let client: TestableAiClient

  beforeEach(() => {
    client = makeTestClient()
  })

  it('keeps current agent messages as assistant', () => {
    const msg = makeMessageEvent('assistant', 'Sway', 'I analyzed the code')
    const result = client.callConvertAgentMessages([msg], 'Sway')

    expect(result).toHaveLength(1)
    expect(result[0]).toBeInstanceOf(MessageEvent)
    expect(result[0].role).toBe('assistant')
    expect(result[0].name).toBe('Sway')
    expect(result[0].content[0].content).toBe('I analyzed the code')
  })

  it('converts other agent messages to user role with XML tags', () => {
    const msg = makeMessageEvent('assistant', 'Reviewer', 'The code looks good')
    const result = client.callConvertAgentMessages([msg], 'Sway')

    expect(result).toHaveLength(1)
    expect(result[0]).toBeInstanceOf(MessageEvent)
    expect(result[0].role).toBe('user')
    expect(result[0].name).toBe('Reviewer')
    expect(result[0].content[0].content).toBe('<agent=Reviewer>The code looks good</agent>')
  })

  it('preserves user messages unchanged', () => {
    const msg = makeMessageEvent('user', 'vincent', 'please review this')
    const result = client.callConvertAgentMessages([msg], 'Sway')

    expect(result[0].role).toBe('user')
    expect(result[0].content[0].content).toBe('please review this')
  })

  it('handles mixed conversation with multiple agents', () => {
    const messages = [
      makeMessageEvent('user', 'vincent', 'start the review'),
      makeMessageEvent('assistant', 'Sway', 'I will delegate to Reviewer'),
      makeMessageEvent('assistant', 'Reviewer', 'Here is my review'),
      makeMessageEvent('assistant', 'Sway', 'I received the review'),
    ]

    const result = client.callConvertAgentMessages(messages, 'Sway')

    expect(result[0].role).toBe('user') // user message unchanged
    expect(result[1].role).toBe('assistant') // current agent unchanged
    expect(result[2].role).toBe('user') // other agent converted
    expect(result[2].content[0].content).toBe('<agent=Reviewer>Here is my review</agent>')
    expect(result[3].role).toBe('assistant') // current agent unchanged
  })

  it('preserves image content unchanged during conversion', () => {
    const msg = new MessageEvent({
      role: 'assistant',
      name: 'Reviewer',
      content: [
        { type: 'text', content: 'Here is a screenshot' },
        { type: 'image', content: 'base64data', mimeType: 'image/png' },
      ],
    })

    const result = client.callConvertAgentMessages([msg], 'Sway')

    expect(result[0].role).toBe('user')
    expect(result[0].content[0].type).toBe('text')
    expect(result[0].content[0].content).toBe('<agent=Reviewer>Here is a screenshot</agent>')
    expect(result[0].content[1].type).toBe('image')
    expect(result[0].content[1].content).toBe('base64data')
  })

  it('handles multi-line message content', () => {
    const msg = makeMessageEvent('assistant', 'Reviewer', 'Line 1\nLine 2\nLine 3')
    const result = client.callConvertAgentMessages([msg], 'Sway')

    expect(result[0].content[0].content).toBe('<agent=Reviewer>Line 1\nLine 2\nLine 3</agent>')
  })

  it('does not convert messages without a name', () => {
    const msg = new MessageEvent({
      role: 'assistant',
      name: undefined as any,
      content: [{ type: 'text', content: 'anonymous message' }],
    })

    const result = client.callConvertAgentMessages([msg], 'Sway')

    // No name means we cannot identify the agent, so no conversion
    expect(result[0].role).toBe('assistant')
    expect(result[0].content[0].content).toBe('anonymous message')
  })

  it('passes through non-MessageEvent messages unchanged', () => {
    const toolRequest = new ToolRequestEvent({
      name: 'read_file',
      args: '{"path": "test.ts"}',
    })
    const toolResponse = new ToolResponseEvent({
      toolRequestId: toolRequest.toolRequestId,
      output: 'file content',
    })
    const summary = new SummaryEvent({ summary: 'Previous conversation summary' })
    const answer = new AnswerEvent({ answer: 'yes' })

    const result = client.callConvertAgentMessages([toolRequest, toolResponse, summary, answer], 'Sway')

    expect(result[0]).toBe(toolRequest)
    expect(result[1]).toBe(toolResponse)
    expect(result[2]).toBe(summary)
    expect(result[3]).toBe(answer)
  })

  it('preserves timestamp of converted messages', () => {
    const msg = makeMessageEvent('assistant', 'Reviewer', 'content')
    const originalTimestamp = msg.timestamp

    const result = client.callConvertAgentMessages([msg], 'Sway')

    expect(result[0].timestamp).toBe(originalTimestamp)
  })
})
