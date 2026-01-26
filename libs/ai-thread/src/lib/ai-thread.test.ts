import { MessageEvent, ToolCall, ToolRequestEvent, ToolResponse, ToolResponseEvent } from '@coday/model'
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
      thread.addUserMessage('user1', { type: 'text', content: 'test message' })

      const messages = (await thread.getMessages(undefined, undefined)).messages
      expect(messages).toHaveLength(1)
      expect(messages[0]).toBeInstanceOf(MessageEvent)
      expect(messages[0]!.type).toBe(MessageEvent.type)
      expect((messages[0] as MessageEvent).content).toEqual([{ type: 'text', content: 'test message' }])
    })

    it('should return a copy of messages', async () => {
      thread.addUserMessage('user1', { type: 'text', content: 'test message' })

      const messages1 = (await thread.getMessages(undefined, undefined)).messages
      const messages2 = (await thread.getMessages(undefined, undefined)).messages

      expect(messages1).not.toBe(messages2) // Different array instances
      expect(messages1[0]).toBe(messages2[0]) // Same message objects
    })

    it('should update modifiedDate when user message is added', async () => {
      // Create a thread with a specific past date
      const pastDate = new Date('2024-01-01T00:00:00.000Z').toISOString()
      const testThread = new AiThread({
        id: 'test-thread',
        username,
        modifiedDate: pastDate,
      })

      expect(testThread.modifiedDate).toBe(pastDate)

      const beforeAdd = new Date().toISOString()
      testThread.addUserMessage('user1', { type: 'text', content: 'test message' })

      // ModifiedDate should be updated to current time
      expect(testThread.modifiedDate).not.toBe(pastDate)
      expect(testThread.modifiedDate >= beforeAdd).toBe(true)
    })

    it('should update modifiedDate when agent message is added', async () => {
      // Create a thread with a specific past date
      const pastDate = new Date('2024-01-01T00:00:00.000Z').toISOString()
      const testThread = new AiThread({
        id: 'test-thread',
        username,
        modifiedDate: pastDate,
      })

      expect(testThread.modifiedDate).toBe(pastDate)

      const beforeAdd = new Date().toISOString()
      testThread.addAgentMessage('agent1', { type: 'text', content: 'agent response' })

      // ModifiedDate should be updated to current time
      expect(testThread.modifiedDate).not.toBe(pastDate)
      expect(testThread.modifiedDate >= beforeAdd).toBe(true)
    })
  })

  describe('tool calls and responses', () => {
    it('should add tool calls with response pair', async () => {
      const call = createToolCall('test-tool', '{"arg": "value"}', 'test-id')
      thread.addToolCalls('agent1', [call])

      // Add matching response to keep the pair
      const response = createToolResponse('test-id', 'test-tool', 'test response')
      thread.addToolResponses('user1', [response])

      const messages = (await thread.getMessages(undefined, undefined)).messages
      expect(messages).toHaveLength(2)
      expect(messages[0]).toBeInstanceOf(ToolRequestEvent)
      expect(messages[1]).toBeInstanceOf(ToolResponseEvent)
      expect((messages[0] as ToolRequestEvent).name).toBe('test-tool')
      expect((messages[1] as ToolResponseEvent).toolRequestId).toBe('test-id')
    })

    it('should skip tool calls with missing required fields', async () => {
      const invalidCall = { name: 'test' } as ToolCall
      thread.addToolCalls('agent1', [invalidCall])

      expect((await thread.getMessages(undefined, undefined)).messages).toHaveLength(0)
    })

    it('should remove orphaned tool calls without responses', async () => {
      const call = createToolCall('test-tool', '{"arg": "value"}')
      thread.addToolCalls('agent1', [call])

      const messages = (await thread.getMessages(undefined, undefined)).messages
      // Orphaned tool request should be removed by cleanToolRequestResponseConsistency
      expect(messages).toHaveLength(0)
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

  describe('tool request-response consistency cleaning', () => {
    beforeEach(() => {
      thread = new AiThread({
        id: 'test-thread',
        username: 'testuser',
        messages: [],
      })
    })

    it('should remove orphaned tool requests without responses', async () => {
      const messages = [
        new MessageEvent({ role: 'user', content: [{ type: 'text', content: 'Hello' }] }),
        new ToolRequestEvent({ name: 'test-tool', args: '{}', toolRequestId: 'orphaned-request' }),
        new MessageEvent({ role: 'assistant', content: [{ type: 'text', content: 'Done' }] }),
      ]
      thread['messages'] = messages

      const result = await thread.getMessages(undefined, undefined)

      // Should remove the orphaned tool request
      expect(result.messages).toHaveLength(2)
      expect(result.messages[0]).toBeInstanceOf(MessageEvent)
      expect(result.messages[1]).toBeInstanceOf(MessageEvent)
      expect(result.messages.some((m) => m instanceof ToolRequestEvent)).toBe(false)
    })

    it('should remove orphaned tool responses without requests', async () => {
      const messages = [
        new MessageEvent({ role: 'user', content: [{ type: 'text', content: 'Hello' }] }),
        new ToolResponseEvent({ toolRequestId: 'missing-request', output: 'result' }),
        new MessageEvent({ role: 'assistant', content: [{ type: 'text', content: 'Done' }] }),
      ]
      thread['messages'] = messages

      const result = await thread.getMessages(undefined, undefined)

      // Should remove the orphaned tool response
      expect(result.messages).toHaveLength(2)
      expect(result.messages[0]).toBeInstanceOf(MessageEvent)
      expect(result.messages[1]).toBeInstanceOf(MessageEvent)
      expect(result.messages.some((m) => m instanceof ToolResponseEvent)).toBe(false)
    })

    it('should remove tool responses without toolRequestId', async () => {
      const brokenResponse = new ToolResponseEvent({ toolRequestId: '', output: 'result' })
      // Simulate a broken response by removing the toolRequestId
      brokenResponse.toolRequestId = undefined as any

      const messages = [
        new MessageEvent({ role: 'user', content: [{ type: 'text', content: 'Hello' }] }),
        brokenResponse,
        new MessageEvent({ role: 'assistant', content: [{ type: 'text', content: 'Done' }] }),
      ]
      thread['messages'] = messages

      const result = await thread.getMessages(undefined, undefined)

      // Should remove the broken tool response
      expect(result.messages).toHaveLength(2)
      expect(result.messages[0]).toBeInstanceOf(MessageEvent)
      expect(result.messages[1]).toBeInstanceOf(MessageEvent)
      expect(result.messages.some((m) => m instanceof ToolResponseEvent)).toBe(false)
    })

    it('should keep valid tool request-response pairs', async () => {
      const messages = [
        new MessageEvent({ role: 'user', content: [{ type: 'text', content: 'Hello' }] }),
        new ToolRequestEvent({ name: 'test-tool', args: '{}', toolRequestId: 'valid-request' }),
        new ToolResponseEvent({ toolRequestId: 'valid-request', output: 'result' }),
        new MessageEvent({ role: 'assistant', content: [{ type: 'text', content: 'Done' }] }),
      ]
      thread['messages'] = messages

      const result = await thread.getMessages(undefined, undefined)

      // Should keep all messages
      expect(result.messages).toHaveLength(4)
      expect(result.messages[0]).toBeInstanceOf(MessageEvent)
      expect(result.messages[1]).toBeInstanceOf(ToolRequestEvent)
      expect(result.messages[2]).toBeInstanceOf(ToolResponseEvent)
      expect(result.messages[3]).toBeInstanceOf(MessageEvent)
    })

    it('should handle mixed scenarios with both valid and invalid pairs', async () => {
      const messages = [
        new MessageEvent({ role: 'user', content: [{ type: 'text', content: 'Hello' }] }),
        new ToolRequestEvent({ name: 'orphaned-tool', args: '{}', toolRequestId: 'orphaned' }),
        new ToolRequestEvent({ name: 'valid-tool', args: '{}', toolRequestId: 'valid' }),
        new ToolResponseEvent({ toolRequestId: 'valid', output: 'valid result' }),
        new ToolResponseEvent({ toolRequestId: 'missing', output: 'orphaned result' }),
        new MessageEvent({ role: 'assistant', content: [{ type: 'text', content: 'Done' }] }),
      ]
      thread['messages'] = messages

      const result = await thread.getMessages(undefined, undefined)

      // Should keep: user message, valid tool pair, assistant message
      expect(result.messages).toHaveLength(4)
      expect(result.messages[0]).toBeInstanceOf(MessageEvent)
      expect(result.messages[1]).toBeInstanceOf(ToolRequestEvent)
      expect((result.messages[1] as ToolRequestEvent).toolRequestId).toBe('valid')
      expect(result.messages[2]).toBeInstanceOf(ToolResponseEvent)
      expect((result.messages[2] as ToolResponseEvent).toolRequestId).toBe('valid')
      expect(result.messages[3]).toBeInstanceOf(MessageEvent)
    })

    it('should handle budget truncation that splits tool request-response pairs', async () => {
      // Create messages with known sizes to control budget truncation precisely
      const messages = [
        // Early messages (will be in overflow due to budget limit)
        new MessageEvent({ role: 'user', content: [{ type: 'text', content: 'Early message '.repeat(10) }] }), // ~150 chars
        new ToolRequestEvent({ name: 'early-tool', args: '{"data": "early"}', toolRequestId: 'early-request' }), // ~100 chars
        new ToolResponseEvent({ toolRequestId: 'early-request', output: 'Early tool response '.repeat(5) }), // ~100 chars

        // Middle messages (truncation point - request kept, response goes to overflow)
        new MessageEvent({ role: 'user', content: [{ type: 'text', content: 'Middle message' }] }), // ~20 chars
        new ToolRequestEvent({ name: 'split-tool', args: '{"split": true}', toolRequestId: 'split-request' }), // ~80 chars
        new ToolResponseEvent({ toolRequestId: 'split-request', output: 'This response will be truncated' }), // ~50 chars

        // Recent messages (will be kept within budget)
        new MessageEvent({ role: 'assistant', content: [{ type: 'text', content: 'Recent response' }] }), // ~20 chars
      ]
      thread['messages'] = messages

      // Verify we start with 7 messages total
      expect(messages).toHaveLength(7)

      // Set budget to approximately 150 chars - should keep last ~3 messages but split the tool pair
      const result = await thread.getMessages(150, undefined)

      // Count message types in result
      const toolRequests = result.messages.filter((m) => m instanceof ToolRequestEvent) as ToolRequestEvent[]
      const toolResponses = result.messages.filter((m) => m instanceof ToolResponseEvent) as ToolResponseEvent[]
      const messageEvents = result.messages.filter((m) => m instanceof MessageEvent) as MessageEvent[]

      // Specific assertions on message counts and content
      expect(result.messages.length).toBeGreaterThan(0)
      expect(result.messages.length).toBeLessThan(7) // Should be truncated

      // Should have exactly the recent assistant message
      expect(messageEvents).toHaveLength(1)
      expect(messageEvents[0]?.role).toBe('assistant')
      expect((messageEvents[0]?.content[0] as any)?.content).toBe('Recent response')

      // The split pair should be completely removed (both request and response)
      const splitRequestPresent = toolRequests.some((req) => req.toolRequestId === 'split-request')
      const splitResponsePresent = toolResponses.some((res) => res.toolRequestId === 'split-request')
      expect(splitRequestPresent).toBe(false)
      expect(splitResponsePresent).toBe(false)

      // Should not have any orphaned tool requests or responses
      expect(toolRequests.every((req) => toolResponses.some((res) => res.toolRequestId === req.toolRequestId))).toBe(
        true
      )

      expect(toolResponses.every((res) => toolRequests.some((req) => req.toolRequestId === res.toolRequestId))).toBe(
        true
      )

      // Early complete pair should not be present (due to budget truncation)
      const earlyRequestPresent = toolRequests.some((req) => req.toolRequestId === 'early-request')
      const earlyResponsePresent = toolResponses.some((res) => res.toolRequestId === 'early-request')
      expect(earlyRequestPresent).toBe(false)
      expect(earlyResponsePresent).toBe(false)

      // Should have no tool pairs at all due to budget constraints and cleaning
      expect(toolRequests).toHaveLength(0)
      expect(toolResponses).toHaveLength(0)

      expect(result.compacted).toBe(true)
    })

    it('should work with charBudget when no truncation occurs', async () => {
      const messages = [
        new MessageEvent({ role: 'user', content: [{ type: 'text', content: 'Hello' }] }),
        new ToolRequestEvent({ name: 'orphaned-tool', args: '{}', toolRequestId: 'orphaned' }),
        new ToolRequestEvent({ name: 'valid-tool', args: '{}', toolRequestId: 'valid' }),
        new ToolResponseEvent({ toolRequestId: 'valid', output: 'valid result' }),
        new MessageEvent({ role: 'assistant', content: [{ type: 'text', content: 'Done' }] }),
      ]
      thread['messages'] = messages

      // Verify we start with 5 messages
      expect(messages).toHaveLength(5)

      // Use a large character budget that should include all messages
      const result = await thread.getMessages(1000, undefined)

      // Count message types in result
      const toolRequests = result.messages.filter((m) => m instanceof ToolRequestEvent) as ToolRequestEvent[]
      const toolResponses = result.messages.filter((m) => m instanceof ToolResponseEvent) as ToolResponseEvent[]
      const messageEvents = result.messages.filter((m) => m instanceof MessageEvent) as MessageEvent[]

      // Should remove orphaned tool request and keep valid pair + 2 messages
      expect(result.messages).toHaveLength(4)
      expect(messageEvents).toHaveLength(2) // user 'Hello' + assistant 'Done'
      expect(toolRequests).toHaveLength(1) // only 'valid' tool request
      expect(toolResponses).toHaveLength(1) // only 'valid' tool response

      // Verify specific messages are present/absent
      expect(toolRequests[0]?.toolRequestId).toBe('valid')
      expect(toolRequests[0]?.name).toBe('valid-tool')
      expect(toolResponses[0]?.toolRequestId).toBe('valid')
      expect(toolResponses[0]?.getTextOutput()).toBe('valid result')

      // Verify orphaned tool request was removed
      expect(toolRequests.some((req) => req.toolRequestId === 'orphaned')).toBe(false)

      // Verify message events content
      const userMessage = messageEvents.find((m) => m.role === 'user')
      const assistantMessage = messageEvents.find((m) => m.role === 'assistant')
      expect(userMessage).toBeDefined()
      expect(assistantMessage).toBeDefined()
      expect((userMessage!.content[0] as any).content).toBe('Hello')
      expect((assistantMessage!.content[0] as any).content).toBe('Done')
    })
  })
})
