import { AiThread } from '../ai-thread/ai-thread'
import { ThreadSummary } from '../ai-thread/ai-thread.types'
import { ThreadRepository } from './thread.repository'
import { Database } from 'sqlite'
import { DatabaseProvider } from './database-provider'
import { MessageEvent, ToolRequestEvent, ToolResponseEvent, SummaryEvent } from '@coday/coday-events'

/**
 * SQLite-based implementation of ThreadRepository
 *
 * Stores threads and messages in a centralized SQLite database.
 * Maintains compatibility with existing ThreadRepository interface.
 */
export class SqliteThreadRepository implements ThreadRepository {
  private codayHomePath: string
  private db: Database | null = null
  private initialized = false

  constructor(codayHomePath: string) {
    this.codayHomePath = codayHomePath
  }

  /**
   * Initialize database connection and schema
   */
  private async ensureInitialized(): Promise<void> {
    if (this.initialized) return

    this.db = await DatabaseProvider.getDatabase(this.codayHomePath)
    await this.createSchema()
    this.initialized = true
  }

  /**
   * Create database schema if it doesn't exist
   */
  private async createSchema(): Promise<void> {
    if (!this.db) throw new Error('Database not initialized')

    await this.db.exec(`
      CREATE TABLE IF NOT EXISTS threads (
        id TEXT PRIMARY KEY,
        project_id TEXT NOT NULL,
        username TEXT NOT NULL,
        name TEXT NOT NULL,
        summary TEXT,
        created_date TEXT NOT NULL,
        modified_date TEXT NOT NULL,
        price REAL DEFAULT 0,
        starring TEXT DEFAULT '[]',  -- JSON array of usernames
        data TEXT DEFAULT '{}'  -- Unstructured JSON (cache markers, etc.)
      );
      
      CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY,
        thread_id TEXT NOT NULL,
        timestamp TEXT NOT NULL,
        type TEXT NOT NULL,
        role TEXT,
        name TEXT,
        content TEXT NOT NULL,  -- JSON string
        args TEXT,
        output TEXT,
        tool_request_id TEXT,
        summary TEXT,
        length INTEGER DEFAULT 0,
        FOREIGN KEY(thread_id) REFERENCES threads(id) ON DELETE CASCADE
      );
      
      -- Index for filtering threads by project
      CREATE INDEX IF NOT EXISTS idx_threads_project 
        ON threads(project_id, modified_date DESC);
      
      -- Index for filtering threads by user
      CREATE INDEX IF NOT EXISTS idx_threads_user 
        ON threads(project_id, username, modified_date DESC);
      
      -- Composite index for message queries
      CREATE INDEX IF NOT EXISTS idx_messages_thread_time 
        ON messages(thread_id, timestamp);
    `)
  }

  /**
   * Get a thread by ID with all its messages
   */
  async getById(projectId: string, threadId: string): Promise<AiThread | null> {
    await this.ensureInitialized()
    if (!this.db) throw new Error('Database not initialized')

    // Get thread metadata
    const threadRow = await this.db.get<any>('SELECT * FROM threads WHERE project_id = ? AND id = ?', [
      projectId,
      threadId,
    ])

    if (!threadRow) return null

    // Get all messages
    const messageRows = await this.db.all<any[]>(
      'SELECT * FROM messages WHERE thread_id = ? ORDER BY timestamp',
      threadId
    )

    // Reconstruct thread object
    const messages = messageRows
      .map((row) => this.rowToEvent(row))
      .filter(
        (event): event is MessageEvent | ToolRequestEvent | ToolResponseEvent | SummaryEvent => event !== undefined
      )

    return new AiThread({
      id: threadRow.id,
      username: threadRow.username,
      projectId: threadRow.project_id,
      name: threadRow.name,
      summary: threadRow.summary || undefined,
      createdDate: threadRow.created_date,
      modifiedDate: threadRow.modified_date,
      price: threadRow.price,
      starring: JSON.parse(threadRow.starring),
      messages,
    })
  }

  /**
   * Convert database row to CodayEvent
   */
  private rowToEvent(row: any): MessageEvent | ToolRequestEvent | ToolResponseEvent | SummaryEvent | undefined {
    const baseEvent = {
      timestamp: row.timestamp,
      type: row.type,
      length: row.length || 0,
    }

    // Reconstruct event based on type
    switch (row.type) {
      case 'MessageEvent':
        return new MessageEvent({
          ...baseEvent,
          role: row.role,
          name: row.name,
          content: JSON.parse(row.content),
        })
      case 'ToolRequestEvent':
        return new ToolRequestEvent({
          ...baseEvent,
          name: row.name,
          args: row.args,
          toolRequestId: row.tool_request_id,
        })
      case 'ToolResponseEvent':
        return new ToolResponseEvent({
          ...baseEvent,
          toolRequestId: row.tool_request_id,
          output: row.output ? JSON.parse(row.output) : row.output,
        })
      case 'SummaryEvent':
        return new SummaryEvent({
          ...baseEvent,
          summary: row.summary,
        })
      default:
        return undefined
    }
  }

  /**
   * Save a thread (create or update)
   */
  async save(projectId: string, thread: AiThread): Promise<AiThread> {
    await this.ensureInitialized()
    if (!this.db) throw new Error('Database not initialized')

    // Start transaction
    await this.db.run('BEGIN TRANSACTION')

    try {
      // Save thread metadata
      await this.db.run(
        `INSERT OR REPLACE INTO threads 
         (id, project_id, username, name, summary, created_date, modified_date, price, starring, data) 
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          thread.id,
          projectId,
          thread.username,
          thread.name,
          thread.summary || null,
          thread.createdDate,
          thread.modifiedDate,
          thread.price,
          JSON.stringify(thread.starring),
          JSON.stringify(thread.data || {}),
        ]
      )

      // Delete existing messages for this thread
      await this.db.run('DELETE FROM messages WHERE thread_id = ?', thread.id)

      // Get all messages (not the compacted ones)
      const allMessages = thread.getAllMessages()

      // Insert all messages
      for (const message of allMessages) {
        await this.saveMessage(thread.id, message)
      }

      // Commit transaction
      await this.db.run('COMMIT')

      return thread
    } catch (error) {
      // Rollback on error
      await this.db.run('ROLLBACK')
      throw error
    }
  }

  /**
   * Save a single message
   */
  private async saveMessage(
    threadId: string,
    message: MessageEvent | ToolRequestEvent | ToolResponseEvent | SummaryEvent
  ): Promise<void> {
    if (!this.db) throw new Error('Database not initialized')

    const baseValues = [message.timestamp, message.type, threadId, message.length]

    // Extract type-specific fields
    let role = null
    let name = null
    let content = '{}'
    let args = null
    let output = null
    let toolRequestId = null
    let summary = null

    if ('role' in message) role = message.role
    if ('name' in message) name = message.name
    if ('content' in message) content = JSON.stringify(message.content)
    if ('args' in message) args = message.args
    if ('output' in message)
      output = typeof message.output === 'string' ? message.output : JSON.stringify(message.output)
    if ('toolRequestId' in message) toolRequestId = message.toolRequestId
    if ('summary' in message) summary = message.summary

    await this.db.run(
      `INSERT INTO messages 
       (id, thread_id, timestamp, type, role, name, content, args, output, tool_request_id, summary, length) 
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        message.timestamp, // Use timestamp as ID
        threadId,
        ...baseValues.slice(0, 2), // timestamp, type
        role,
        name,
        content,
        args,
        output,
        toolRequestId,
        summary,
        message.length,
      ]
    )
  }

  /**
   * List threads for a project, optionally filtered by username
   */
  async listByProject(projectId: string, username?: string): Promise<ThreadSummary[]> {
    await this.ensureInitialized()
    if (!this.db) throw new Error('Database not initialized')

    const query = username
      ? 'SELECT * FROM threads WHERE project_id = ? AND username = ? ORDER BY modified_date DESC'
      : 'SELECT * FROM threads WHERE project_id = ? ORDER BY modified_date DESC'

    const params = username ? [projectId, username] : [projectId]

    const rows = await this.db.all<any[]>(query, params)

    return rows.map((row) => ({
      id: row.id,
      username: row.username,
      projectId: row.project_id,
      name: row.name,
      summary: row.summary || undefined,
      createdDate: row.created_date,
      modifiedDate: row.modified_date,
      price: row.price,
      starring: JSON.parse(row.starring),
    }))
  }

  /**
   * Delete a thread and all its messages
   */
  async delete(projectId: string, threadId: string): Promise<boolean> {
    await this.ensureInitialized()
    if (!this.db) throw new Error('Database not initialized')

    const result = await this.db.run('DELETE FROM threads WHERE project_id = ? AND id = ?', [projectId, threadId])

    // Messages are automatically deleted via CASCADE
    return (result.changes || 0) > 0
  }
}
