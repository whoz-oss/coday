import sqlite3 from 'sqlite3'
import { open, Database } from 'sqlite'
import path from 'path'
import fs from 'fs/promises'

/**
 * Database Provider - Centralized SQLite connection management
 *
 * Provides a single global database instance for all Coday data.
 * Handles connection lifecycle, WAL mode, and proper shutdown.
 *
 * Architecture notes:
 * - Single database file: ~/.coday/coday.db
 * - WAL mode enabled for better concurrent read/write performance
 * - Busy timeout set to 5000ms to handle concurrent access
 * - Async API using 'sqlite' wrapper over 'sqlite3'
 * - All entities partitioned by project_id (except users/groups)
 */
export class DatabaseProvider {
  private static instance: Database | null = null
  private static codayHomePath: string | null = null

  /**
   * Get or create the global database connection
   *
   * @param codayHomePath - Path to .coday directory (e.g., ~/.coday)
   * @returns Promise<Database> - SQLite database instance
   */
  static async getDatabase(codayHomePath: string): Promise<Database> {
    // Return cached instance if same path
    if (this.instance && this.codayHomePath === codayHomePath) {
      return this.instance
    }

    // Close existing instance if path changed
    if (this.instance && this.codayHomePath !== codayHomePath) {
      await this.instance.close()
      this.instance = null
    }

    // Ensure .coday directory exists
    await fs.mkdir(codayHomePath, { recursive: true })

    const dbPath = path.join(codayHomePath, 'coday.db')

    // Open database connection
    const db = await open({
      filename: dbPath,
      driver: sqlite3.Database,
    })

    // Configure for concurrent access
    await db.run('PRAGMA journal_mode = WAL')
    await db.run('PRAGMA busy_timeout = 5000')
    await db.run('PRAGMA foreign_keys = ON')

    // Cache the instance
    this.instance = db
    this.codayHomePath = codayHomePath

    return db
  }

  /**
   * Close the global database connection
   * Used during graceful shutdown
   */
  static async close(): Promise<void> {
    if (this.instance) {
      await this.instance.close()
      this.instance = null
      this.codayHomePath = null
    }
  }

  /**
   * Check if database is connected
   */
  static isConnected(): boolean {
    return this.instance !== null
  }
}
