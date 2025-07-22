import { MessageEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/coday-events'
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
    it('should add and retrieve messages', async () => {
      thread.addUserMessage('user1', { type: 'text', content: 'test message'})

      const messages = (await thread.getMessages(undefined, undefined)).messages
      expect(messages).toHaveLength(1)
      expect(messages[0]).toBeInstanceOf(MessageEvent)
      expect(messages[0]!.type).toBe(MessageEvent.type)
      expect((messages[0] as MessageEvent).content).toEqual([{ type: 'text', content: 'test message'}])
    })

    it('should return a copy of messages', async () => {
      thread.addUserMessage('user1', { type: 'text', content: 'test message'})

      const messages1 = (await thread.getMessages(undefined, undefined)).messages
      const messages2 = (await thread.getMessages(undefined, undefined)).messages

      expect(messages1).not.toBe(messages2) // Different array instances
      expect(messages1[0]).toBe(messages2[0]) // Same message objects
    })
  })

  describe('tool calls and responses', () => {
    it('should add tool calls', async () => {
      const call = createToolCall('test-tool', '{"arg": "value"}')
      thread.addToolCalls('agent1', [call])

      const messages = (await thread.getMessages(undefined, undefined)).messages
      expect(messages).toHaveLength(1)
      expect(messages[0]).toBeInstanceOf(ToolRequestEvent)
      expect((messages[0] as ToolRequestEvent).name).toBe('test-tool')
    })

    it('should skip tool calls with missing required fields', async () => {
      const invalidCall = { name: 'test' } as ToolCall
      thread.addToolCalls('agent1', [invalidCall])

      expect((await thread.getMessages(undefined, undefined)).messages).toHaveLength(0)
    })

    it('should add tool response and keep only latest similar calls', async () => {
      // Add two similar tool calls
      const call1 = createToolCall('test-tool', '{"arg": "value"}', 'id1')
      const call2 = createToolCall('test-tool', '{"arg": "value"}', 'id2')

      thread.addToolCalls('agent1', [call1])
      thread.addToolCalls('agent1', [call2])

      // Add response to second call
      const response = createToolResponse('id2', 'test-tool', 'test response')
      thread.addToolResponses('user1', [response])

      const messages = (await thread.getMessages(undefined, undefined)).messages
      expect(messages).toHaveLength(2) // Only the latest request and its response remain

      const request = messages.find((m) => m instanceof ToolRequestEvent) as ToolRequestEvent
      const response2 = messages.find((m) => m instanceof ToolResponseEvent) as ToolResponseEvent

      expect(request?.toolRequestId).toBe('id2')
      expect(response2?.toolRequestId).toBe('id2')
    })

    it('should handle multiple different tool calls independently', async () => {
      // Add two different tool calls
      const call1 = createToolCall('tool1', '{"arg": "value"}', 'id1')
      const call2 = createToolCall('tool2', '{"arg": "value"}', 'id2')

      thread.addToolCalls('agent1', [call1, call2])

      // Add response to both
      const response1 = createToolResponse('id1', 'tool1', 'response1')
      const response2 = createToolResponse('id2', 'tool2', 'response2')

      thread.addToolResponses('user1', [response1, response2])

      const messages = (await thread.getMessages(undefined, undefined)).messages
      expect(messages).toHaveLength(4) // Both requests and responses remain

      const requests = messages.filter((m) => m instanceof ToolRequestEvent) as ToolRequestEvent[]
      const responses = messages.filter((m) => m instanceof ToolResponseEvent) as ToolResponseEvent[]

      expect(requests).toHaveLength(2)
      expect(responses).toHaveLength(2)
      expect(requests.map((r) => r.name)).toContain('tool1')
      expect(requests.map((r) => r.name)).toContain('tool2')
    })

    it('should ignore responses without matching requests', async () => {
      const response = createToolResponse('non-existent', 'test-tool', 'test')
      thread.addToolResponses('user1', [response])

      expect((await thread.getMessages(undefined, undefined)).messages).toHaveLength(0)
    })

    it('should handle args comparison properly', async () => {
      // Add two calls with same tool but different args
      const call1 = createToolCall('test-tool', '{"arg": "value1"}', 'id1')
      const call2 = createToolCall('test-tool', '{"arg": "value2"}', 'id2')

      thread.addToolCalls('agent1', [call1, call2])

      // Add responses to both
      const response1 = createToolResponse('id1', 'test-tool', 'response1')
      const response2 = createToolResponse('id2', 'test-tool', 'response2')

      thread.addToolResponses('user1', [response1, response2])

      const messages = (await thread.getMessages(undefined, undefined)).messages
      expect(messages).toHaveLength(4) // Both sets remain as args are different

      const requests = messages.filter((m) => m instanceof ToolRequestEvent) as ToolRequestEvent[]
      expect(requests.map((r) => r.args)).toContain('{"arg": "value1"}')
      expect(requests.map((r) => r.args)).toContain('{"arg": "value2"}')
    })
  })

  describe('message removal', () => {
    it('should maintain order after removals', async () => {
      // Add a sequence of messages with some to be removed
      thread.addUserMessage('user1', { type: 'text', content: 'message1' })

      const call1 = createToolCall('test-tool', '{"arg": "value"}', 'id1')
      thread.addToolCalls('agent1', [call1])

      thread.addUserMessage('user1', { type: 'text', content: 'message2' })

      const call2 = createToolCall('test-tool', '{"arg": "value"}', 'id2')
      thread.addToolCalls('agent1', [call2])

      const response = createToolResponse('id2', 'test-tool', 'response')
      thread.addToolResponses('user1', [response])

      const messages = (await thread.getMessages(undefined, undefined)).messages
      expect(messages[0]?.type).toBe(MessageEvent.type) // First user message
      expect(messages[1]?.type).toBe(MessageEvent.type) // Second user message
      expect(messages[2]?.type).toBe(ToolRequestEvent.type) // Latest tool request
      expect(messages[3]?.type).toBe(ToolResponseEvent.type) // Its response
      expect(messages).toHaveLength(4)
    })
  })

  describe('thread forking', () => {
    it('should fork a thread without an agent name', async () => {
      // Add some initial messages
      thread.addUserMessage('user1', { type: 'text', content: 'Initial message' })
      thread.addAgentMessage('agent1', { type: 'text', content: 'Agent response' })

      // Fork the thread
      const forkedThread = thread.fork()

      // Check forked thread properties
      expect(forkedThread).not.toBe(thread)
      expect(forkedThread.id).toBe(thread.id)
      expect(forkedThread.username).toBe(thread.username)
      expect(forkedThread.name).toContain('Forked')
      expect(forkedThread.delegationDepth).toBe(1)

      // Check messages were copied
      const forkedMessages = (await forkedThread.getMessages(undefined, undefined)).messages
      expect(forkedMessages).toHaveLength(2)
      expect(forkedMessages[0]?.type).toBe(MessageEvent.type)
      expect(forkedMessages[1]?.type).toBe(MessageEvent.type)
    })

    it('should fork a thread with an agent name', () => {
      // Add some initial messages
      thread.addUserMessage('user1', { type: 'text', content: 'Initial message' })
      thread.addAgentMessage('agent1', { type: 'text', content: 'Agent response' })

      // Fork the thread for a specific agent
      const forkedThread = thread.fork('agent2')

      // Check forked thread properties
      expect(forkedThread.id).toBe(thread.id)
      expect(forkedThread.username).toBe(thread.username)
      expect(forkedThread.name).toContain('Delegated to agent2')
      expect(forkedThread.delegationDepth).toBe(1)
    })

    it('should return existing forked thread for an agent', () => {
      // Fork a thread for an agent
      const forkedThread1 = thread.fork('agent2')
      const forkedThread2 = thread.fork('agent2')

      // Check they are the same instance
      expect(forkedThread1).toBe(forkedThread2)
    })

    it('should support multiple forked threads for different agents', () => {
      const forkedThread1 = thread.fork('agent1')
      const forkedThread2 = thread.fork('agent2')

      // Check they are different threads
      expect(forkedThread1).not.toBe(forkedThread2)
      expect(forkedThread1.id).toBe(thread.id)
      expect(forkedThread2.id).toBe(thread.id)
    })

    it('should copy thread price when forking', () => {
      // Set a price on the original thread
      thread.price = 1.5

      // Fork the thread
      const forkedThread = thread.fork('agent2')
      forkedThread.addUsage({ price: 0.5 })

      // Check price is copied
      expect(forkedThread.price).toBe(0.5)
      expect(forkedThread.totalPrice).toBe(2)
    })

    it('should create a new messages array when forking', async () => {
      // Add messages to original thread
      thread.addUserMessage('user1', { type: 'text', content: 'Initial message' })

      // Fork the thread
      const forkedThread = thread.fork('agent2')

      // Modify messages in forked thread
      forkedThread.addAgentMessage('agent2', { type: 'text', content: 'Forked response' })

      // Check original thread is unchanged
      const originalMessages = (await thread.getMessages(undefined, undefined)).messages
      expect(originalMessages).toHaveLength(1)
    })
  })

  describe('thread merging', () => {
    it('should merge a forked thread back to parent', () => {
      // Add some initial price
      thread.price = 1.5

      // Fork and add price to forked thread
      const forkedThread = thread.fork('agent2')
      forkedThread.addUsage({ price: 0.5 })

      // Merge the forked thread
      thread.merge(forkedThread)

      // Check total price is updated
      expect(thread.totalPrice).toBe(2)

      // Check forked thread is still in the registry
      expect(thread['forkedThreads'].get('agent2')).toBe(forkedThread)
    })

    it('should handle merging threads with zero price', () => {
      const initialPrice = thread.price

      // Create and merge a forked thread with zero price
      const forkedThread = thread.fork('agent2')
      thread.merge(forkedThread)

      // Price should remain unchanged
      expect(thread.price).toBe(initialPrice)
    })
  })
})
