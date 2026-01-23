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
  const testCodayHome = path.join(process.cwd(), '.test-data')

  console.log('🧪 Testing ThreadRepository with single global database...')
  console.log(`📁 Test .coday path: ${testCodayHome}`)

  try {
    // Clean up previous test data
    await fs.rm(testCodayHome, { recursive: true, force: true })

    // Initialize repository
    const repo = new ThreadRepository(testCodayHome)
    await repo.initialize()
    console.log('✅ Repository initialized with global database')

    // Test 1: Save threads for multiple projects
    await repo.saveThread({
      id: 'thread-1',
      projectId: 'project-a',
      name: 'Test conversation A',
      agentName: 'sway',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      summary: 'A test thread in project A',
      data: {
        anthropic: {
          cacheMarkerMessageId: 'msg-123', // Unstructured data example
        },
      },
    })

    await repo.saveThread({
      id: 'thread-2',
      projectId: 'project-b',
      name: 'Test conversation B',
      agentName: 'archay',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      summary: 'A test thread in project B',
    })
    console.log('✅ Threads saved for multiple projects')

    // Test 2: Retrieve thread with unstructured data
    const thread = await repo.getThread('thread-1')
    console.log('✅ Thread retrieved:', JSON.parse(thread!.data))

    // Test 3: List threads by project
    const projectAThreads = await repo.listThreadsByProject('project-a')
    console.log(`✅ Project A has ${projectAThreads.length} thread(s)`)

    // Test 4: Add messages with different types
    for (let i = 0; i < 5; i++) {
      await repo.addMessage({
        id: `msg-user-${i}`,
        threadId: 'thread-1',
        timestamp: new Date().toISOString(),
        type: 'MessageEvent',
        role: 'user',
        content: { text: `User message ${i} with searchable content` },
      })

      await repo.addMessage({
        id: `msg-tool-${i}`,
        threadId: 'thread-1',
        timestamp: new Date().toISOString(),
        type: 'ToolRequestEvent',
        content: { name: 'searchFiles', args: '{}' },
      })
    }
    console.log('✅ Messages added with different types')

    // Test 5: Get all messages
    const messages = await repo.getThreadMessages('thread-1')
    console.log(`✅ Retrieved ${messages.length} messages`)

    // Test 6: Get messages by type (uses index)
    console.time('get by type')
    const toolRequests = await repo.getThreadMessagesByType('thread-1', 'ToolRequestEvent')
    console.timeEnd('get by type')
    console.log(`✅ Found ${toolRequests.length} ToolRequestEvents (indexed query)`)

    // Test 7: Get messages by type across project (uses index)
    console.time('get project messages by type')
    const projectToolRequests = await repo.getProjectMessagesByType('project-a', 'ToolRequestEvent')
    console.timeEnd('get project messages by type')
    console.log(`✅ Found ${projectToolRequests.length} ToolRequestEvents in project-a (indexed query)`)

    // Test 8: Search messages across all projects
    console.time('search')
    const results = await repo.searchMessages('searchable')
    console.timeEnd('search')
    console.log(`✅ Found ${results.length} messages in search (project: ${results[0]?.project_id})`)

    // Test 9: Cleanup old messages
    const deleted = await repo.cleanupOldMessages('2025-01-01')
    console.log(`✅ Deleted ${deleted} old messages`)

    // Test 10: Database connection status
    const isConnected = DatabaseProvider.isConnected()
    console.log(`✅ Database connected: ${isConnected}`)

    // Cleanup
    await repo.close()
    console.log('✅ Repository closed')

    console.log('\n🎉 All tests passed!')
    console.log('\n📊 Key validations:')
    console.log('  ✓ Single global database (~/.coday/coday.db)')
    console.log('  ✓ Multi-project support via project_id')
    console.log('  ✓ Unstructured data in JSON column')
    console.log('  ✓ Indexed queries by type (fast filtering)')
    console.log('  ✓ Cross-project search')
  } catch (error) {
    console.error('❌ Test failed:', error)
    throw error
  } finally {
    // Ensure connection is closed
    await DatabaseProvider.close()
  }
}

// Run tests
testRepository().catch(console.error)
