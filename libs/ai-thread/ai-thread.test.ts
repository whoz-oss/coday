// eslint-disable-next-line @nx/enforce-module-boundaries
import { MessageEvent, ToolRequestEvent, ToolResponseEvent } from '../shared'
// eslint-disable-next-line @nx/enforce-module-boundaries
import { ToolCall, ToolResponse } from '../integration/tool-call'
import { AiThread } from './ai-thread'

const createToolCall = (name: string, args: string, id?: string): ToolCall => ({
  name,
  args,
  id: id ?? new Date().toISOString(),
})

const createToolResponse = (id: string, name: string, response: string): ToolResponse => ({
  id,
  name,
  response,
})

const username = 'john_doe'

describe('AiThread', () => {
  let thread: AiThread

  beforeEach(() => {
    thread = new AiThread({ id: 'test-thread', username })
  })

  describe('messages management', () => {
    it('should add and retrieve messages', () => {
      thread.addUserMessage('user1', 'test message')

      const messages = thread.getMessages()
      expect(messages).toHaveLength(1)
      expect(messages[0]).toBeInstanceOf(MessageEvent)
      expect(messages[0].type).toBe(MessageEvent.type)
    })

    it('should return a copy of messages', () => {
      thread.addUserMessage('user1', 'test message')

      const messages1 = thread.getMessages()
      const messages2 = thread.getMessages()

      expect(messages1).not.toBe(messages2) // Different array instances
      expect(messages1[0]).toBe(messages2[0]) // Same message objects
    })
  })

  describe('tool calls and responses', () => {
    it('should add tool calls', () => {
      const call = createToolCall('test-tool', '{"arg": "value"}')
      thread.addToolCalls('agent1', [call])

      const messages = thread.getMessages()
      expect(messages).toHaveLength(1)
      expect(messages[0]).toBeInstanceOf(ToolRequestEvent)
      expect((messages[0] as ToolRequestEvent).name).toBe('test-tool')
    })

    it('should skip tool calls with missing required fields', () => {
      const invalidCall = { name: 'test' } as ToolCall
      thread.addToolCalls('agent1', [invalidCall])

      expect(thread.getMessages()).toHaveLength(0)
    })

    it('should add tool response and keep only latest similar calls', () => {
      // Add two similar tool calls
      const call1 = createToolCall('test-tool', '{"arg": "value"}', 'id1')
      const call2 = createToolCall('test-tool', '{"arg": "value"}', 'id2')

      thread.addToolCalls('agent1', [call1])
      thread.addToolCalls('agent1', [call2])

      // Add response to second call
      const response = createToolResponse('id2', 'test-tool', 'test response')
      thread.addToolResponses('user1', [response])

      const messages = thread.getMessages()
      expect(messages).toHaveLength(2) // Only the latest request and its response remain

      const request = messages.find((m) => m instanceof ToolRequestEvent) as ToolRequestEvent
      const response2 = messages.find((m) => m instanceof ToolResponseEvent) as ToolResponseEvent

      expect(request?.toolRequestId).toBe('id2')
      expect(response2?.toolRequestId).toBe('id2')
    })

    it('should handle multiple different tool calls independently', () => {
      // Add two different tool calls
      const call1 = createToolCall('tool1', '{"arg": "value"}', 'id1')
      const call2 = createToolCall('tool2', '{"arg": "value"}', 'id2')

      thread.addToolCalls('agent1', [call1, call2])

      // Add response to both
      const response1 = createToolResponse('id1', 'tool1', 'response1')
      const response2 = createToolResponse('id2', 'tool2', 'response2')

      thread.addToolResponses('user1', [response1, response2])

      const messages = thread.getMessages()
      expect(messages).toHaveLength(4) // Both requests and responses remain

      const requests = messages.filter((m) => m instanceof ToolRequestEvent) as ToolRequestEvent[]
      const responses = messages.filter((m) => m instanceof ToolResponseEvent) as ToolResponseEvent[]

      expect(requests).toHaveLength(2)
      expect(responses).toHaveLength(2)
      expect(requests.map((r) => r.name)).toContain('tool1')
      expect(requests.map((r) => r.name)).toContain('tool2')
    })

    it('should ignore responses without matching requests', () => {
      const response = createToolResponse('non-existent', 'test-tool', 'test')
      thread.addToolResponses('user1', [response])

      expect(thread.getMessages()).toHaveLength(0)
    })

    it('should handle args comparison properly', () => {
      // Add two calls with same tool but different args
      const call1 = createToolCall('test-tool', '{"arg": "value1"}', 'id1')
      const call2 = createToolCall('test-tool', '{"arg": "value2"}', 'id2')

      thread.addToolCalls('agent1', [call1, call2])

      // Add responses to both
      const response1 = createToolResponse('id1', 'test-tool', 'response1')
      const response2 = createToolResponse('id2', 'test-tool', 'response2')

      thread.addToolResponses('user1', [response1, response2])

      const messages = thread.getMessages()
      expect(messages).toHaveLength(4) // Both sets remain as args are different

      const requests = messages.filter((m) => m instanceof ToolRequestEvent) as ToolRequestEvent[]
      expect(requests.map((r) => r.args)).toContain('{"arg": "value1"}')
      expect(requests.map((r) => r.args)).toContain('{"arg": "value2"}')
    })
  })

  describe('message removal', () => {
    it('should maintain order after removals', () => {
      // Add a sequence of messages with some to be removed
      thread.addUserMessage('user1', 'message1')

      const call1 = createToolCall('test-tool', '{"arg": "value"}', 'id1')
      thread.addToolCalls('agent1', [call1])

      thread.addUserMessage('user1', 'message2')

      const call2 = createToolCall('test-tool', '{"arg": "value"}', 'id2')
      thread.addToolCalls('agent1', [call2])

      const response = createToolResponse('id2', 'test-tool', 'response')
      thread.addToolResponses('user1', [response])

      const messages = thread.getMessages()
      expect(messages[0].type).toBe(MessageEvent.type) // First user message
      expect(messages[1].type).toBe(MessageEvent.type) // Second user message
      expect(messages[2].type).toBe(ToolRequestEvent.type) // Latest tool request
      expect(messages[3].type).toBe(ToolResponseEvent.type) // Its response
      expect(messages).toHaveLength(4)
    })
  })
})
