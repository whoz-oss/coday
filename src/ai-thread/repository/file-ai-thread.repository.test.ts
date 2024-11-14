import os from 'os'
import path from 'path'
import fs from 'fs/promises'
import * as yaml from 'yaml'
import {FileAiThreadRepository} from './file-ai-thread.repository'
import {AiThread} from '../ai-thread'
import {ThreadRepositoryError} from '../ai-thread.types'

describe('FileAiThreadRepository', () => {
  let repo: FileAiThreadRepository
  let tmpDir: string

  beforeEach(async () => {
    // Create temp directory
    tmpDir = await fs.mkdtemp(
      path.join(os.tmpdir(), 'file-ai-thread-repository-test-')
    )
    repo = new FileAiThreadRepository(tmpDir)
    await repo.initialize()
  })

  afterEach(async () => {
    // Clean up temp directory and all its contents
    await fs.rm(tmpDir, { recursive: true, force: true })
  })

  describe('initialization', () => {
    it('should create threads directory on initialize', async () => {
      // Create a new temp dir without initialization
      const newTmpDir = await fs.mkdtemp(
        path.join(os.tmpdir(), 'file-ai-thread-new-test-')
      )
      // Remove the directory created by mkdtemp
      await fs.rm(newTmpDir, { recursive: true, force: true })
      
      const newRepo = new FileAiThreadRepository(newTmpDir)
      
      // Directory should not exist yet
      await expect(fs.stat(newTmpDir)).rejects.toBeTruthy()
      
      // Initialize should create it
      await newRepo.initialize()
      
      // Directory should now exist
      const stats = await fs.stat(newTmpDir)
      expect(stats.isDirectory()).toBe(true)
      
      // Clean up
      await fs.rm(newTmpDir, { recursive: true, force: true })
    })

    it('should handle existing directory without error', async () => {
      // Second initialization should not throw
      await expect(repo.initialize()).resolves.toBeUndefined()
    })
  })

  describe('thread operations', () => {
    it('should save and retrieve a thread', async () => {
      const thread = new AiThread({
        id: 'test-id',
        name: 'Test Thread',
        summary: 'Test Summary',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01'
      })

      // Save thread
      await repo.save(thread)

      // Check the actual file content
      const filePath = path.join(tmpDir, 'test-thread.yml')
      const fileContent = await fs.readFile(filePath, 'utf-8')
      const savedData = yaml.parse(fileContent)
      
      // Check saved data matches thread (ignoring _messages array)
      const {_messages, ...threadData} = savedData
      expect(threadData).toEqual({
        id: 'test-id',
        name: 'Test Thread',
        summary: 'Test Summary',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01'
      })

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

    it('should list all valid threads', async () => {
      const threads = [
        new AiThread({
          id: 'test-1',
          name: 'Test 1',
          summary: 'Summary 1',
          createdDate: '2024-01-01',
          modifiedDate: '2024-01-01'
        }),
        new AiThread({
          id: 'test-2',
          name: 'Test 2',
          summary: 'Summary 2',
          createdDate: '2024-01-01',
          modifiedDate: '2024-01-01'
        })
      ]

      // Save threads
      await Promise.all(threads.map(thread => repo.save(thread)))

      // List threads
      const listed = await repo.listThreads()
      expect(listed).toHaveLength(2)
      expect(listed.map(t => t.id)).toEqual(expect.arrayContaining(['test-1', 'test-2']))
    })

    it('should delete an existing thread', async () => {
      const thread = new AiThread({
        id: 'test-delete',
        name: 'Test Delete',
        summary: 'To be deleted',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01'
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
    it('should sanitize thread names for file paths', async () => {
      const thread = new AiThread({
        id: 'test-id',
        name: 'Test & Special @ Characters!',
        summary: 'Test Summary',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01'
      })

      await repo.save(thread)

      // Check if file exists with sanitized name
      const files = await fs.readdir(tmpDir)
      expect(files).toContain('test-special-characters.yml')
    })

    it('should handle name collisions with different ids', async () => {
      const thread1 = new AiThread({
        id: 'id-1',
        name: 'Same Name',
        summary: 'First thread',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01'
      })

      const thread2 = new AiThread({
        id: 'id-2',
        name: 'Same Name',
        summary: 'Second thread',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01'
      })

      await repo.save(thread1)
      await repo.save(thread2)

      // Both threads should be retrievable
      expect(await repo.getById('id-1')).not.toBeNull()
      expect(await repo.getById('id-2')).not.toBeNull()

      // Files should have unique names
      const files = await fs.readdir(tmpDir)
      expect(files).toContain('same-name.yml')
      expect(files).toContain('same-name-id-2.yml')
    })
  })

  describe('error handling', () => {
    it('should handle corrupted YAML files when listing', async () => {
      // Create a corrupted file
      await fs.writeFile(
        path.join(tmpDir, 'corrupted.yml'),
        'invalid: yaml: content:',
        'utf-8'
      )

      // Create a valid thread
      const thread = new AiThread({
        id: 'valid',
        name: 'Valid Thread',
        summary: 'Valid',
        createdDate: '2024-01-01',
        modifiedDate: '2024-01-01'
      })
      await repo.save(thread)

      // Should list only valid thread
      const listed = await repo.listThreads()
      expect(listed).toHaveLength(1)
      expect(listed[0].id).toBe('valid')
    })

    it('should throw ThreadRepositoryError on directory creation failure', async () => {
      // Create temp path
      const newTmpDir = path.join(os.tmpdir(), 'file-ai-thread-error-test')
      
      // Create it as a file to cause mkdir to fail
      await fs.writeFile(newTmpDir, '', { mode: 0o444 }) // read-only file

      const errorRepo = new FileAiThreadRepository(newTmpDir)
      await expect(errorRepo.initialize())
        .rejects
        .toThrow(ThreadRepositoryError)

      // Clean up
      await fs.rm(newTmpDir, { force: true })
    })
  })
})