// eslint-disable-next-line @nx/enforce-module-boundaries
import { migrateData } from '../utils/data-migration'
import { aiThreadMigrations } from './ai-thread.migrations'

describe('AI Thread migrations', () => {
  describe('messageEventToMessageContent migration', () => {
    it('should migrate MessageEvent.content from string to MessageContent', () => {
      // Create a sample AI thread with old string content format
      const aiThread = {
        id: 'test-thread-id',
        name: 'Test Thread',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Test summary',
        price: 0,
        messages: [
          {
            type: 'message',
            timestamp: '2024-01-01T00:00:00.000Z',
            role: 'user',
            content: 'Hello, this is a test message',
          },
          {
            type: 'message',
            timestamp: '2024-01-01T00:01:00.000Z',
            role: 'assistant',
            content: 'This is a response message',
          },
          {
            type: 'toolRequest',
            timestamp: '2024-01-01T00:02:00.000Z',
            toolRequestId: 'tool-request-1',
            name: 'readFile',
            args: '{"filePath": "test.txt"}',
          },
          {
            type: 'toolResponse',
            timestamp: '2024-01-01T00:03:00.000Z',
            toolRequestId: 'tool-request-1',
            output: 'File content here',
          },
        ],
      }

      // Apply migration
      const result = migrateData(aiThread, aiThreadMigrations)

      // Check that version is set (2 migrations applied)
      expect(result.version).toBe(3)

      // Check that message events are migrated
      expect(result.messages).toHaveLength(4)

      // Check first message (user message)
      const userMessage = result.messages[0]
      expect(userMessage.type).toBe('message')
      expect(userMessage.role).toBe('user')
      expect(userMessage.content).toBeDefined()
      expect(userMessage.content.content).toEqual([
        {
          type: 'text',
          content: 'Hello, this is a test message',
        },
      ])

      // Check second message (assistant message)
      const assistantMessage = result.messages[1]
      expect(assistantMessage.type).toBe('message')
      expect(assistantMessage.role).toBe('assistant')
      expect(assistantMessage.content).toBeDefined()
      expect(assistantMessage.content.content).toEqual([
        {
          type: 'text',
          content: 'This is a response message',
        },
      ])

      // Check that non-message events are not affected
      const toolRequest = result.messages[2]
      expect(toolRequest.type).toBe('toolRequest')
      expect(toolRequest.toolRequestId).toBe('tool-request-1')
      expect(toolRequest.name).toBe('readFile')
      expect(toolRequest.args).toBe('{"filePath": "test.txt"}')

      const toolResponse = result.messages[3]
      expect(toolResponse.type).toBe('toolResponse')
      expect(toolResponse.toolRequestId).toBe('tool-request-1')
      expect(toolResponse.output).toBe('File content here')
    })

    it('should handle empty messages array', () => {
      // Create a sample AI thread with no messages
      const aiThread = {
        id: 'test-thread-id',
        name: 'Empty Thread',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Empty test thread',
        price: 0,
        messages: [],
      }

      // Apply migration
      const result = migrateData(aiThread, aiThreadMigrations)

      // Check that version is set
      expect(result.version).toBe(3)

      // Check that messages array is still empty
      expect(result.messages).toHaveLength(0)
    })

    it('should handle thread with missing or null messages property', () => {
      // Create a sample AI thread without messages property
      const aiThreadWithoutMessages = {
        id: 'test-thread-id',
        name: 'Thread Without Messages',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Thread without messages property',
        price: 0,
      }

      // Apply migration
      const result1 = migrateData(aiThreadWithoutMessages, aiThreadMigrations)
      expect(result1.version).toBe(3)
      expect(result1.messages).toEqual([])

      // Create a sample AI thread with null messages
      const aiThreadWithNullMessages = {
        id: 'test-thread-id',
        name: 'Thread With Null Messages',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Thread with null messages',
        price: 0,
        messages: null,
      }

      // Apply migration
      const result2 = migrateData(aiThreadWithNullMessages, aiThreadMigrations)
      expect(result2.version).toBe(3)
      expect(result2.messages).toEqual([])

      // Create a sample AI thread with non-array messages
      const aiThreadWithInvalidMessages = {
        id: 'test-thread-id',
        name: 'Thread With Invalid Messages',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Thread with invalid messages',
        price: 0,
        messages: 'not an array',
      }

      // Apply migration
      const result3 = migrateData(aiThreadWithInvalidMessages, aiThreadMigrations)
      expect(result3.version).toBe(3)
      expect(result3.messages).toEqual([])
    })

    it('should handle thread with only non-message events', () => {
      // Create a sample AI thread with only tool events
      const aiThread = {
        id: 'test-thread-id',
        name: 'Tool Only Thread',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Thread with only tool events',
        price: 0,
        messages: [
          {
            type: 'toolRequest',
            timestamp: '2024-01-01T00:00:00.000Z',
            toolRequestId: 'tool-request-1',
            name: 'listFiles',
            args: '{"path": "."}',
          },
          {
            type: 'toolResponse',
            timestamp: '2024-01-01T00:01:00.000Z',
            toolRequestId: 'tool-request-1',
            output: 'file1.txt\nfile2.txt',
          },
        ],
      }

      // Apply migration
      const result = migrateData(aiThread, aiThreadMigrations)

      // Check that version is set
      expect(result.version).toBe(3)

      // Check that tool events are unchanged
      expect(result.messages).toHaveLength(2)
      expect(result.messages[0].type).toBe('toolRequest')
      expect(result.messages[1].type).toBe('toolResponse')

      // Verify tool events are not modified
      expect(result.messages[0]).toEqual(aiThread.messages[0])
      expect(result.messages[1]).toEqual(aiThread.messages[1])
    })

    it('should handle mixed message types correctly', () => {
      // Create a sample AI thread with mixed message types
      const aiThread = {
        id: 'test-thread-id',
        name: 'Mixed Thread',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Thread with mixed message types',
        price: 0,
        messages: [
          {
            type: 'message',
            timestamp: '2024-01-01T00:00:00.000Z',
            role: 'user',
            content: 'Please read the file',
          },
          {
            type: 'toolRequest',
            timestamp: '2024-01-01T00:01:00.000Z',
            toolRequestId: 'tool-request-1',
            name: 'readFile',
            args: '{"filePath": "example.txt"}',
          },
          {
            type: 'toolResponse',
            timestamp: '2024-01-01T00:02:00.000Z',
            toolRequestId: 'tool-request-1',
            output: 'File content example',
          },
          {
            type: 'message',
            timestamp: '2024-01-01T00:03:00.000Z',
            role: 'assistant',
            content: 'I have read the file and here is the content',
          },
        ],
      }

      // Apply migration
      const result = migrateData(aiThread, aiThreadMigrations)

      // Check that version is set
      expect(result.version).toBe(3)

      // Check that all messages are present
      expect(result.messages).toHaveLength(4)

      // Check first message (user message) - should be migrated
      const userMessage = result.messages[0]
      expect(userMessage.type).toBe('message')
      expect(userMessage.content.content).toEqual([
        {
          type: 'text',
          content: 'Please read the file',
        },
      ])

      // Check tool request - should be unchanged
      const toolRequest = result.messages[1]
      expect(toolRequest.type).toBe('toolRequest')
      expect(toolRequest.toolRequestId).toBe('tool-request-1')

      // Check tool response - should be unchanged
      const toolResponse = result.messages[2]
      expect(toolResponse.type).toBe('toolResponse')
      expect(toolResponse.output).toBe('File content example')

      // Check last message (assistant message) - should be migrated
      const assistantMessage = result.messages[3]
      expect(assistantMessage.type).toBe('message')
      expect(assistantMessage.content.content).toEqual([
        {
          type: 'text',
          content: 'I have read the file and here is the content',
        },
      ])
    })

    it('should preserve other message properties during migration', () => {
      // Create a sample AI thread with additional properties
      const aiThread = {
        id: 'test-thread-id',
        name: 'Property Test Thread',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Thread to test property preservation',
        price: 0,
        messages: [
          {
            type: 'message',
            timestamp: '2024-01-01T00:00:00.000Z',
            role: 'user',
            content: 'Test message with extra properties',
            metadata: {
              source: 'web',
              priority: 'high',
            },
          },
        ],
      }

      // Apply migration
      const result = migrateData(aiThread, aiThreadMigrations)

      // Check that the message is migrated correctly
      const message = result.messages[0]
      expect(message.type).toBe('message')
      expect(message.timestamp).toBe('2024-01-01T00:00:00.000Z')
      expect(message.role).toBe('user')
      expect(message.metadata).toEqual({
        source: 'web',
        priority: 'high',
      })

      // Check that content is properly migrated
      expect(message.content.content).toEqual([
        {
          type: 'text',
          content: 'Test message with extra properties',
        },
      ])
    })
  })

  describe('addStarringField migration', () => {
    it('should add starring field as empty array when not present', () => {
      // Create a sample AI thread without starring field
      const aiThread = {
        id: 'test-thread-id',
        name: 'Test Thread',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Test summary',
        price: 0,
        messages: [],
      }

      // Apply migrations
      const result = migrateData(aiThread, aiThreadMigrations)

      // Check that starring field is added
      expect(result.starring).toEqual([])
      expect(result.version).toBe(3)
    })

    it('should preserve existing starring field', () => {
      // Create a sample AI thread with existing starring field
      const aiThread = {
        id: 'test-thread-id',
        name: 'Test Thread',
        username: 'test-user',
        createdDate: '2024-01-01T00:00:00.000Z',
        modifiedDate: '2024-01-01T00:00:00.000Z',
        summary: 'Test summary',
        price: 0,
        messages: [],
        starring: ['user1@example.com', 'user2@example.com'],
      }

      // Apply migrations
      const result = migrateData(aiThread, aiThreadMigrations)

      // Check that starring field is preserved
      expect(result.starring).toEqual(['user1@example.com', 'user2@example.com'])
      expect(result.version).toBe(3)
    })
  })
})
