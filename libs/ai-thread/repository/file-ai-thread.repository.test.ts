import * as os from 'os'
import * as path from 'path'
import * as fs from 'fs/promises'
import * as yaml from 'yaml'
import { FileAiThreadRepository } from './file-ai-thread.repository'
import { AiThread } from '../ai-thread'
import { ThreadRepositoryError } from '../ai-thread.types'

describe('FileAiThreadRepository', () => {
  let repo: FileAiThreadRepository
  let tmpDir: string
  const username = 'john_doe'

  beforeEach(async () => {
    // Create temp directory
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'file-ai-thread-repository-test-'))
    repo = new FileAiThreadRepository(tmpDir)
  })

  afterEach(async () => {
    // Clean up temp directory and all its contents
    await fs.rm(tmpDir, { recursive: true, force: true })
  })

  describe('initialization', () => {
    it('should create threads directory on first operation', async () => {
      // Create a new temp dir without initialization
      const newTmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'file-ai-thread-new-test-'))
      // Remove the directory created by mkdtemp
      await fs.rm(newTmpDir, { recursive: true, force: true })

      // Just instantiate, don't wait for init
      const newRepo = new FileAiThreadRepository(newTmpDir)

      // List should trigger initialization
      await newRepo.listThreadsByUsername(username)

      // Directory should now exist
      const stats = await fs.stat(newTmpDir)
      expect(stats.isDirectory()).toBe(true)

      // Clean up
      await fs.rm(newTmpDir, { recursive: true, force: true })
    })
  })

  describe('thread operations', () => {
    it('should save and retrieve a thread', async () => {
      const threadInput = {
        id: 'test-id',
        username,
        name: 'Test Thread',
        summary: 'Test Summary',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01',
      }
      const thread = new AiThread({
        id: 'test-id',
        username,
        name: 'Test Thread',
        summary: 'Test Summary',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01',
      })

      // Save thread
      await repo.save(thread)

      // Check the actual file content
      const files = await fs.readdir(tmpDir)
      const filePath = path.join(tmpDir, files[0]!)
      const fileContent = await fs.readFile(filePath, 'utf-8')
      const savedData = yaml.parse(fileContent)

      // Check saved data matches thread (ignoring _messages array)
      expect(savedData).toMatchObject(threadInput)

      // Retrieve thread through repository
      const retrieved = await repo.getById('test-id')
      expect(retrieved).not.toBeNull()
      expect(retrieved?.id).toBe('test-id')
      expect(retrieved?.name).toBe('Test Thread')
    })

    it('should handle missing thread gracefully', async () => {
      const retrieved = await repo.getById('non-existent')
      expect(retrieved).toBeNull()
    })

    it('should update file content when saving with new name', async () => {
      // Create and save initial thread
      const thread = new AiThread({
        id: 'test-id',
        username,
        name: 'Original Name',
        summary: 'Test Summary',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01',
      })
      await repo.save(thread)

      // Get the original file name (should be test-id.yml)
      const originalFiles = await fs.readdir(tmpDir)
      expect(originalFiles).toHaveLength(1)
      expect(originalFiles[0]).toBe('test-id.yml')

      // Save same thread with new name
      thread.name = 'New Name'
      await repo.save(thread)

      // Should still have only one file with same name (id-based)
      const files = await fs.readdir(tmpDir)
      expect(files).toHaveLength(1)
      expect(files[0]).toBe('test-id.yml')

      // File should contain updated name
      const fileContent = yaml.parse(await fs.readFile(path.join(tmpDir, files[0]!), 'utf-8'))
      expect(fileContent.id).toBe('test-id')
      expect(fileContent.name).toBe('New Name')
    })

    it('should list all valid threads', async () => {
      const threads = [
        new AiThread({
          id: 'test-1',
          username,
          name: 'Test 1',
          summary: 'Summary 1',
          createdDate: '2024-01-01',
          modifiedDate: '2024-01-01',
        }),
        new AiThread({
          id: 'test-2',
          username,
          name: 'Test 2',
          summary: 'Summary 2',
          createdDate: '2024-01-01',
          modifiedDate: '2024-01-01',
        }),
      ]

      // Save threads
      await Promise.all(threads.map((thread) => repo.save(thread)))

      // List threads
      const listed = await repo.listThreadsByUsername(username)
      expect(listed).toHaveLength(2)
      expect(listed.map((t) => t.id)).toEqual(expect.arrayContaining(['test-1', 'test-2']))
    })

    it('should delete an existing thread', async () => {
      const thread = new AiThread({
        id: 'test-delete',
        username,
        name: 'Test Delete',
        summary: 'To be deleted',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01',
      })

      // Save and verify thread exists
      await repo.save(thread)
      expect(await repo.getById('test-delete')).not.toBeNull()

      // Delete thread
      const deleted = await repo.delete('test-delete')
      expect(deleted).toBe(true)

      // Verify thread is gone
      expect(await repo.getById('test-delete')).toBeNull()
    })
  })

  describe('name handling', () => {
    it('should use thread id for filename regardless of special characters in name', async () => {
      const thread = new AiThread({
        id: 'test-id-123',
        username,
        name: 'Test & Special @ Characters!',
        summary: 'Test Summary',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01',
      })

      await repo.save(thread)

      // Check if file exists with id-based name (no sanitization needed)
      const files = await fs.readdir(tmpDir)
      expect(files).toHaveLength(1)
      expect(files[0]).toBe('test-id-123.yml')
    })

    it('should handle name collisions with different ids', async () => {
      // Create and save first thread
      const thread1 = new AiThread({
        id: 'id-1',
        username,
        name: 'Same Name',
        summary: 'First thread',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01',
      })
      await repo.save(thread1)

      // Save second thread with same name but different id
      const thread2 = new AiThread({
        id: 'id-2',
        username,
        name: 'Same Name',
        summary: 'Second thread',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01',
      })
      await repo.save(thread2)

      // Both threads should be retrievable
      const savedThread1 = await repo.getById('id-1')
      const savedThread2 = await repo.getById('id-2')

      expect(savedThread1).not.toBeNull()
      expect(savedThread2).not.toBeNull()
      expect(savedThread1?.id).toBe('id-1')
      expect(savedThread2?.id).toBe('id-2')

      // Files should follow the id-only pattern (no name collision possible)
      const files = await fs.readdir(tmpDir)
      expect(files).toContain('id-1.yml')
      expect(files).toContain('id-2.yml')
      expect(files).toHaveLength(2)
    })
  })

  describe('error handling', () => {
    it('should handle corrupted YAML files when listing', async () => {
      // Create a corrupted file
      await fs.writeFile(path.join(tmpDir, 'corrupted.yml'), 'invalid: yaml: content:', 'utf-8')

      // Should list empty array
      const listed = await repo.listThreadsByUsername(username)
      expect(listed).toHaveLength(0)
    })

    it('should throw ThreadRepositoryError on directory creation failure', async () => {
      // Create temp path
      const newTmpDir = path.join(os.tmpdir(), 'file-ai-thread-error-test')

      // Create it as a file to cause mkdir to fail
      await fs.writeFile(newTmpDir, '', { mode: 0o444 }) // read-only file

      const errorRepo = new FileAiThreadRepository(newTmpDir)
      await expect(errorRepo.listThreadsByUsername(username)).rejects.toThrow(ThreadRepositoryError)

      // Clean up
      await fs.rm(newTmpDir, { force: true })
    })
  })
})
