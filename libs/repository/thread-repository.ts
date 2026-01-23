import { Database } from 'sqlite'
import { DatabaseProvider } from './database-provider'

/**
 * Thread Repository - SQLite-based thread persistence
 *
 * Handles threads and messages with support for:
 * - Structured data (id, name, dates)
 * - Unstructured data (JSON in 'data' column for cache markers, etc.)
 * - Multi-project support via project_id foreign key
 *
 * Schema design:
 * - threads table: Core thread metadata + unstructured 'data' JSON column + project_id
 * - messages table: Individual messages linked to threads
 */

export interface ThreadRow {
  id: string
  project_id: string
  name: string
  agent_name: string | null
  created_at: string
  updated_at: string
  summary: string | null
  data: string // JSON string for unstructured data (cache markers, etc.)
}

export interface MessageRow {
  id: string
  thread_id: string
  timestamp: string
  type: string
  role: string | null
  content: string // JSON string
}

export class ThreadRepository {
  private codayHomePath: string
  private db: Database | null = null

  constructor(codayHomePath: string) {
    this.codayHomePath = codayHomePath
  }

  /**
   * Initialize database connection and schema
   * Must be called before using the repository
   */
  async initialize(): Promise<void> {
    this.db = await DatabaseProvider.getDatabase(this.codayHomePath)
    await this.createSchema()
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
        name TEXT NOT NULL,
        agent_name TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL,
        summary TEXT,
        data TEXT DEFAULT '{}'  -- Unstructured JSON data (cache markers, etc.)
      );
      
      CREATE TABLE IF NOT EXISTS messages (
        id TEXT PRIMARY KEY,
        thread_id TEXT NOT NULL,
        timestamp TEXT NOT NULL,
        type TEXT NOT NULL,
        role TEXT,
        content TEXT NOT NULL,  -- JSON string
        FOREIGN KEY(thread_id) REFERENCES threads(id) ON DELETE CASCADE
      );
      
      -- Index for filtering threads by project
      CREATE INDEX IF NOT EXISTS idx_threads_project 
        ON threads(project_id, updated_at DESC);
      
      -- Composite index for common message queries
      CREATE INDEX IF NOT EXISTS idx_messages_thread_type_time 
        ON messages(thread_id, type, timestamp);
      
      -- Index for searching messages by type across projects
      CREATE INDEX IF NOT EXISTS idx_messages_type_time 
        ON messages(type, timestamp DESC);
    `)
  }

  /**
   * Save or update a thread
   * Handles both structured fields and unstructured data JSON
   */
  async saveThread(thread: {
    id: string
    projectId: string
    name: string
    agentName?: string
    createdAt: string
    updatedAt: string
    summary?: string
    data?: Record<string, any> // Unstructured data (cache markers, etc.)
  }): Promise<void> {
    if (!this.db) throw new Error('Database not initialized')

    await this.db.run(
      `INSERT OR REPLACE INTO threads 
       (id, project_id, name, agent_name, created_at, updated_at, summary, data) 
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        thread.id,
        thread.projectId,
        thread.name,
        thread.agentName || null,
        thread.createdAt,
        thread.updatedAt,
        thread.summary || null,
        JSON.stringify(thread.data || {}),
      ]
    )
  }

  /**
   * Get a thread by ID with its unstructured data
   */
  async getThread(id: string): Promise<ThreadRow | null> {
    if (!this.db) throw new Error('Database not initialized')

    const row = await this.db.get<ThreadRow>('SELECT * FROM threads WHERE id = ?', id)

    return row || null
  }

  /**
   * List threads for a specific project
   */
  async listThreadsByProject(projectId: string, limit = 100): Promise<ThreadRow[]> {
    if (!this.db) throw new Error('Database not initialized')

    return await this.db.all<ThreadRow[]>(
      'SELECT * FROM threads WHERE project_id = ? ORDER BY updated_at DESC LIMIT ?',
      [projectId, limit]
    )
  }

  /**
   * Add a message to a thread
   */
  async addMessage(message: {
    id: string
    threadId: string
    timestamp: string
    type: string
    role?: string
    content: any // Will be stringified to JSON
  }): Promise<void> {
    if (!this.db) throw new Error('Database not initialized')

    await this.db.run(
      `INSERT INTO messages (id, thread_id, timestamp, type, role, content) 
       VALUES (?, ?, ?, ?, ?, ?)`,
      [
        message.id,
        message.threadId,
        message.timestamp,
        message.type,
        message.role || null,
        JSON.stringify(message.content),
      ]
    )
  }

  /**
   * Get all messages for a thread, ordered by timestamp
   */
  async getThreadMessages(threadId: string): Promise<MessageRow[]> {
    if (!this.db) throw new Error('Database not initialized')

    return await this.db.all<MessageRow[]>('SELECT * FROM messages WHERE thread_id = ? ORDER BY timestamp', threadId)
  }

  /**
   * Get messages of a specific type for a thread (uses index)
   */
  async getThreadMessagesByType(threadId: string, type: string): Promise<MessageRow[]> {
    if (!this.db) throw new Error('Database not initialized')

    return await this.db.all<MessageRow[]>(
      'SELECT * FROM messages WHERE thread_id = ? AND type = ? ORDER BY timestamp',
      [threadId, type]
    )
  }

  /**
   * Get all messages of a specific type across a project (uses index)
   */
  async getProjectMessagesByType(projectId: string, type: string, limit = 100): Promise<MessageRow[]> {
    if (!this.db) throw new Error('Database not initialized')

    return await this.db.all<MessageRow[]>(
      `SELECT m.* FROM messages m 
       JOIN threads t ON m.thread_id = t.id 
       WHERE t.project_id = ? AND m.type = ? 
       ORDER BY m.timestamp DESC 
       LIMIT ?`,
      [projectId, type, limit]
    )
  }

  /**
   * Search messages across all threads
   */
  async searchMessages(
    query: string,
    limit = 50
  ): Promise<Array<MessageRow & { thread_name: string; project_id: string }>> {
    if (!this.db) throw new Error('Database not initialized')

    return await this.db.all(
      `SELECT m.*, t.name as thread_name, t.project_id 
       FROM messages m 
       JOIN threads t ON m.thread_id = t.id 
       WHERE m.content LIKE ? 
       ORDER BY m.timestamp DESC 
       LIMIT ?`,
      [`%${query}%`, limit]
    )
  }

  /**
   * Delete old messages before a certain date
   */
  async cleanupOldMessages(beforeDate: string): Promise<number> {
    if (!this.db) throw new Error('Database not initialized')

    const result = await this.db.run('DELETE FROM messages WHERE timestamp < ?', beforeDate)

    return result.changes || 0
  }

  /**
   * Close the database connection
   */
  async close(): Promise<void> {
    if (this.db) {
      await DatabaseProvider.close()
      this.db = null
    }
  }
}
