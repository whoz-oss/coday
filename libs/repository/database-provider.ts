import sqlite3 from 'sqlite3'
import { open, Database } from 'sqlite'
import path from 'path'
import fs from 'fs/promises'

/**
 * Database Provider - Centralized SQLite connection management
 *
 * Provides a singleton-like database instance per project.
 * Handles connection pooling, WAL mode, and proper lifecycle management.
 *
 * Architecture notes:
 * - One database file per project: ~/.coday/projects/{projectName}/coday.db
 * - WAL mode enabled for better concurrent read/write performance
 * - Busy timeout set to 5000ms to handle concurrent access
 * - Async API using 'sqlite' wrapper over 'sqlite3'
 */
export class DatabaseProvider {
  private static instances: Map<string, Database> = new Map()

  /**
   * Get or create a database connection for a project
   *
   * @param projectPath - Full path to the project directory
   * @returns Promise<Database> - SQLite database instance
   */
  static async getDatabase(projectPath: string): Promise<Database> {
    // Use projectPath as cache key
    if (this.instances.has(projectPath)) {
      return this.instances.get(projectPath)!
    }

    // Ensure project directory exists
    await fs.mkdir(projectPath, { recursive: true })

    const dbPath = path.join(projectPath, 'coday.db')

    // Open database connection
    const db = await open({
      filename: dbPath,
      driver: sqlite3.Database,
    })

    // Configure for concurrent access
    await db.run('PRAGMA journal_mode = WAL')
    await db.run('PRAGMA busy_timeout = 5000')

    // Cache the instance
    this.instances.set(projectPath, db)

    return db
  }

  /**
   * Close a specific database connection
   *
   * @param projectPath - Full path to the project directory
   */
  static async closeDatabase(projectPath: string): Promise<void> {
    const db = this.instances.get(projectPath)
    if (db) {
      await db.close()
      this.instances.delete(projectPath)
    }
  }

  /**
   * Close all database connections
   * Used during graceful shutdown
   */
  static async closeAll(): Promise<void> {
    const closingPromises = Array.from(this.instances.values()).map((db) => db.close())
    await Promise.all(closingPromises)
    this.instances.clear()
  }

  /**
   * Get the number of active database connections
   * Useful for monitoring and debugging
   */
  static getActiveConnectionCount(): number {
    return this.instances.size
  }
}
