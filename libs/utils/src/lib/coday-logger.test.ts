import { promises as fs } from 'fs'
import * as path from 'path'
import { CodayLoggerUtils } from './coday-logger.utils'

describe('CodayLogger', () => {
  const testLogDir = path.join(process.cwd(), 'logs', 'usage')

  beforeEach(async () => {
    // Clean up any existing test log files
    try {
      const files = await fs.readdir(testLogDir)
      for (const file of files) {
        if (file.endsWith('.jsonl')) {
          await fs.unlink(path.join(testLogDir, file))
        }
      }
    } catch {
      // Directory might not exist, that's ok
    }
  })

  afterEach(async () => {
    // Clean up test files
    try {
      const files = await fs.readdir(testLogDir)
      for (const file of files) {
        if (file.endsWith('.jsonl')) {
          await fs.unlink(path.join(testLogDir, file))
        }
      }
    } catch {
      // Ignore cleanup errors
    }
  })

  describe('when logging is disabled', () => {
    it('should not create log files', async () => {
      const logger = new CodayLoggerUtils(false, testLogDir)

      await logger.logAgentUsage('test-user', 'Dev', 'gpt-4o', 0.05)
      await logger.shutdown()

      // No log files should be created
      try {
        const files = await fs.readdir(testLogDir)
        expect(files.filter((f) => f.endsWith('.jsonl'))).toHaveLength(0)
      } catch {
        // Directory doesn't exist, which is expected
        expect(true).toBe(true)
      }
    })
  })

  describe('when logging is enabled', () => {
    it('should create daily log files with correct format', async () => {
      const logger = new CodayLoggerUtils(true, testLogDir, 100) // Very short flush interval for testing

      await logger.logAgentUsage('test-user', 'Dev', 'gpt-4o', 0.05)
      await logger.logAgentUsage('test-user', 'Sway', 'claude-3-sonnet', 0.03)

      // Force immediate flush
      await logger.shutdown()

      // Check that log file was created
      const files = await fs.readdir(testLogDir)
      const jsonlFiles = files.filter((f) => f.endsWith('.jsonl'))
      expect(jsonlFiles).toHaveLength(1)

      // Check file name format (YYYY-MM-DD.jsonl)
      const now = new Date()
      const year = now.getFullYear()
      const month = String(now.getMonth() + 1).padStart(2, '0')
      const day = String(now.getDate()).padStart(2, '0')
      const expectedFileName = `${year}-${month}-${day}.jsonl`
      expect(jsonlFiles[0]).toBe(expectedFileName)

      // Check file content
      const filePath = path.join(testLogDir, jsonlFiles[0]!)
      const content = await fs.readFile(filePath, 'utf8')
      const lines = content.trim().split('\n')

      expect(lines).toHaveLength(2)

      // Parse and validate entries
      const entry1 = JSON.parse(lines[0]!)
      const entry2 = JSON.parse(lines[1]!)

      expect(entry1).toEqual({
        type: 'AGENT_USAGE',
        timestamp: expect.any(String),
        username: 'test-user',
        agent: 'Dev',
        model: 'gpt-4o',
        cost: 0.05,
      })

      expect(entry2).toEqual({
        type: 'AGENT_USAGE',
        timestamp: expect.any(String),
        username: 'test-user',
        agent: 'Sway',
        model: 'claude-3-sonnet',
        cost: 0.03,
      })

      // Validate timestamp format (ISO string)
      expect(new Date(entry1.timestamp).toISOString()).toBe(entry1.timestamp)
      expect(new Date(entry2.timestamp).toISOString()).toBe(entry2.timestamp)
    })

    it('should buffer entries and flush periodically', async () => {
      const logger = new CodayLoggerUtils(true, testLogDir, 200) // 200ms flush interval

      await logger.logAgentUsage('test-user', 'Dev', 'gpt-4o', 0.05)

      // File should not exist yet (buffered)
      try {
        const files = await fs.readdir(testLogDir)
        expect(files.filter((f) => f.endsWith('.jsonl'))).toHaveLength(0)
      } catch {
        // Directory doesn't exist yet, which is expected
      }

      // Wait for flush interval + buffer
      await new Promise((resolve) => setTimeout(resolve, 300))

      // Now file should exist
      const files = await fs.readdir(testLogDir)
      const jsonlFiles = files.filter((f) => f.endsWith('.jsonl'))
      expect(jsonlFiles).toHaveLength(1)

      await logger.shutdown()
    })

    it('should flush immediately when buffer gets large', async () => {
      const logger = new CodayLoggerUtils(true, testLogDir, 10000) // Long flush interval

      // Add 100+ entries to trigger immediate flush
      for (let i = 0; i < 105; i++) {
        await logger.logAgentUsage('test-user', 'Dev', 'gpt-4o', 0.01)
      }

      // Wait a bit for async flush to complete
      await new Promise((resolve) => setTimeout(resolve, 100))

      // File should exist immediately due to buffer size limit
      const files = await fs.readdir(testLogDir)
      const jsonlFiles = files.filter((f) => f.endsWith('.jsonl'))
      expect(jsonlFiles).toHaveLength(1)

      await logger.shutdown()
    })
  })
})
