import { Database } from 'sqlite'
import { DatabaseProvider } from './database-provider'

/**
 * Thread Repository - SQLite-based thread persistence
 *
 * Handles threads and messages with support for:
 * - Structured data (id, name, dates)
 * - Unstructured data (JSON in 'data' column for cache markers, etc.)
 *
 * Schema design:
 * - threads table: Core thread metadata + unstructured 'data' JSON column
 * - messages table: Individual messages linked to threads
 */

export interface ThreadRow {
  id: string
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
  private projectPath: string
  private db: Database | null = null

  constructor(projectPath: string) {
    this.projectPath = projectPath
  }

  /**
   * Initialize database connection and schema
   * Must be called before using the repository
   */
  async initialize(): Promise<void> {
    this.db = await DatabaseProvider.getDatabase(this.projectPath)
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
      
      CREATE INDEX IF NOT EXISTS idx_messages_thread_time 
        ON messages(thread_id, timestamp);
    `)
  }

  /**
   * Save or update a thread
   * Handles both structured fields and unstructured data JSON
   */
  async saveThread(thread: {
    id: string
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
       (id, name, agent_name, created_at, updated_at, summary, data) 
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [
        thread.id,
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
   * Search messages across all threads
   */
  async searchMessages(query: string, limit = 50): Promise<Array<MessageRow & { thread_name: string }>> {
    if (!this.db) throw new Error('Database not initialized')

    return await this.db.all(
      `SELECT m.*, t.name as thread_name 
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
      await DatabaseProvider.closeDatabase(this.projectPath)
      this.db = null
    }
  }
}
