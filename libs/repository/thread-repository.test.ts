import { ThreadRepository } from './thread-repository'
import { DatabaseProvider } from './database-provider'
import fs from 'fs/promises'
import path from 'path'

/**
 * Simple test/validation script for ThreadRepository
 *
 * Run with: pnpm tsx libs/repository/thread-repository.test.ts
 */

async function testRepository() {
  const testProjectPath = path.join(process.cwd(), '.test-data', 'test-project')

  console.log('🧪 Testing ThreadRepository...')
  console.log(`📁 Test project path: ${testProjectPath}`)

  try {
    // Clean up previous test data
    await fs.rm(testProjectPath, { recursive: true, force: true })

    // Initialize repository
    const repo = new ThreadRepository(testProjectPath)
    await repo.initialize()
    console.log('✅ Repository initialized')

    // Test 1: Save a thread with unstructured data
    await repo.saveThread({
      id: 'thread-1',
      name: 'Test conversation',
      agentName: 'sway',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      summary: 'A test thread',
      data: {
        anthropic: {
          cacheMarkerMessageId: 'msg-123', // Unstructured data example
        },
      },
    })
    console.log('✅ Thread saved with unstructured data')

    // Test 2: Retrieve thread
    const thread = await repo.getThread('thread-1')
    console.log('✅ Thread retrieved:', JSON.parse(thread!.data))

    // Test 3: Add messages
    for (let i = 0; i < 10; i++) {
      await repo.addMessage({
        id: `msg-${i}`,
        threadId: 'thread-1',
        timestamp: new Date().toISOString(),
        type: 'MessageEvent',
        role: 'user',
        content: { text: `Message ${i} with searchable content` },
      })
    }
    console.log('✅ Messages added')

    // Test 4: Get messages
    const messages = await repo.getThreadMessages('thread-1')
    console.log(`✅ Retrieved ${messages.length} messages`)

    // Test 5: Search messages
    console.time('search')
    const results = await repo.searchMessages('searchable')
    console.timeEnd('search')
    console.log(`✅ Found ${results.length} messages in search`)

    // Test 6: Cleanup old messages
    const deleted = await repo.cleanupOldMessages('2025-01-01')
    console.log(`✅ Deleted ${deleted} old messages`)

    // Test 7: Connection count
    const count = DatabaseProvider.getActiveConnectionCount()
    console.log(`✅ Active database connections: ${count}`)

    // Cleanup
    await repo.close()
    console.log('✅ Repository closed')

    console.log('\n🎉 All tests passed!')
  } catch (error) {
    console.error('❌ Test failed:', error)
    throw error
  } finally {
    // Ensure all connections are closed
    await DatabaseProvider.closeAll()
  }
}

// Run tests
testRepository().catch(console.error)
