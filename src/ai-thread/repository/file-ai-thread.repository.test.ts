import os from "os"
import path from "path"
import fs from "fs/promises"
import * as yaml from "yaml"
import {FileAiThreadRepository} from "./file-ai-thread.repository"
import {AiThread} from "../ai-thread"
import {ThreadRepositoryError} from "../ai-thread.types"

describe("FileAiThreadRepository", () => {
  let repo: FileAiThreadRepository
  let tmpDir: string
  
  beforeEach(async () => {
    // Create temp directory
    tmpDir = await fs.mkdtemp(
      path.join(os.tmpdir(), "file-ai-thread-repository-test-")
    )
    repo = new FileAiThreadRepository(tmpDir)
  })
  
  afterEach(async () => {
    // Clean up temp directory and all its contents
    await fs.rm(tmpDir, {recursive: true, force: true})
  })
  
  describe("initialization", () => {
    it("should create threads directory on first operation", async () => {
      // Create a new temp dir without initialization
      const newTmpDir = await fs.mkdtemp(
        path.join(os.tmpdir(), "file-ai-thread-new-test-")
      )
      // Remove the directory created by mkdtemp
      await fs.rm(newTmpDir, {recursive: true, force: true})
      
      // Just instantiate, don't wait for init
      const newRepo = new FileAiThreadRepository(newTmpDir)
      
      // List should trigger initialization
      await newRepo.listThreads()
      
      // Directory should now exist
      const stats = await fs.stat(newTmpDir)
      expect(stats.isDirectory()).toBe(true)
      
      // Clean up
      await fs.rm(newTmpDir, {recursive: true, force: true})
    })
  })
  
  describe("thread operations", () => {
    it("should save and retrieve a thread", async () => {
      const threadInput = {
        id: "test-id",
        name: "Test Thread",
        summary: "Test Summary",
        createdDate: "2024-01-01",
        modifiedDate: "2024-01-01"
      }
      const thread = new AiThread({
        id: "test-id",
        name: "Test Thread",
        summary: "Test Summary",
        createdDate: "2024-01-01",
        modifiedDate: "2024-01-01"
      })
      
      // Save thread
      await repo.save(thread)
      
      // Check the actual file content
      const files = await fs.readdir(tmpDir)
      const filePath = path.join(tmpDir, files[0])
      const fileContent = await fs.readFile(filePath, "utf-8")
      const savedData = yaml.parse(fileContent)
      
      // Check saved data matches thread (ignoring _messages array)
      expect(savedData).toMatchObject(threadInput)
      
      // Retrieve thread through repository
      const retrieved = await repo.getById("test-id")
      expect(retrieved).not.toBeNull()
      expect(retrieved?.id).toBe("test-id")
      expect(retrieved?.name).toBe("Test Thread")
    })
    
    it("should handle missing thread gracefully", async () => {
      const retrieved = await repo.getById("non-existent")
      expect(retrieved).toBeNull()
    })
    
    it("should preserve original file when saving with new name", async () => {
      // Create and save initial thread
      const thread = new AiThread({
        id: "test-id",
        name: "Original Name",
        summary: "Test Summary",
        createdDate: "2024-01-01",
        modifiedDate: "2024-01-01"
      })
      await repo.save(thread)
      
      // Get the original file name
      const originalFiles = await fs.readdir(tmpDir)
      const originalFile = originalFiles[0]
      
      // Save same thread with new name
      thread.name = "New Name"
      await repo.save(thread)
      
      // Check both files exist
      const files = await fs.readdir(tmpDir)
      expect(files).toContain(originalFile)
      expect(files.some(f => f.startsWith("new-name"))).toBe(true)
      expect(files).toHaveLength(2)
      
      // Both should contain same thread data (except name)
      const originalContent = yaml.parse(
        await fs.readFile(path.join(tmpDir, originalFile), "utf-8")
      )
      const newFileContent = yaml.parse(
        await fs.readFile(
          path.join(tmpDir, files.find(f => f.startsWith("new-name"))!),
          "utf-8"
        )
      )
      
      expect(originalContent.id).toBe("test-id")
      expect(newFileContent.id).toBe("test-id")
      expect(originalContent.name).toBe("Original Name")
      expect(newFileContent.name).toBe("New Name")
    })
    
    it("should list all valid threads", async () => {
      const threads = [
        new AiThread({
          id: "test-1",
          name: "Test 1",
          summary: "Summary 1",
          createdDate: "2024-01-01",
          modifiedDate: "2024-01-01"
        }),
        new AiThread({
          id: "test-2",
          name: "Test 2",
          summary: "Summary 2",
          createdDate: "2024-01-01",
          modifiedDate: "2024-01-01"
        })
      ]
      
      // Save threads
      await Promise.all(threads.map(thread => repo.save(thread)))
      
      // List threads
      const listed = await repo.listThreads()
      expect(listed).toHaveLength(2)
      expect(listed.map(t => t.id)).toEqual(expect.arrayContaining(["test-1", "test-2"]))
    })
    
    it("should delete an existing thread", async () => {
      const thread = new AiThread({
        id: "test-delete",
        name: "Test Delete",
        summary: "To be deleted",
        createdDate: "2024-01-01",
        modifiedDate: "2024-01-01"
      })
      
      // Save and verify thread exists
      await repo.save(thread)
      expect(await repo.getById("test-delete")).not.toBeNull()
      
      // Delete thread
      const deleted = await repo.delete("test-delete")
      expect(deleted).toBe(true)
      
      // Verify thread is gone
      expect(await repo.getById("test-delete")).toBeNull()
    })
  })
  
  describe("name handling", () => {
    it("should sanitize thread names for file paths", async () => {
      const thread = new AiThread({
        id: "test-id",
        name: "Test & Special @ Characters!",
        summary: "Test Summary",
        createdDate: "2024-01-01",
        modifiedDate: "2024-01-01"
      })
      
      await repo.save(thread)
      
      // Check if file exists with sanitized name
      const files = await fs.readdir(tmpDir)
      const sanitizedFiles = files.filter(f => f.startsWith("test-special-characters"))
      expect(sanitizedFiles).toHaveLength(1)
    })
    
    it("should handle name collisions with different ids", async () => {
      // Create and save first thread
      const thread1 = new AiThread({
        id: "id-1",
        name: "Same Name",
        summary: "First thread",
        createdDate: "2024-01-01",
        modifiedDate: "2024-01-01"
      })
      await repo.save(thread1)
      
      // Save second thread
      const thread2 = new AiThread({
        id: "id-2",
        name: "Same Name",
        summary: "Second thread",
        createdDate: "2024-01-01",
        modifiedDate: "2024-01-01"
      })
      await repo.save(thread2)
      
      // Both threads should be retrievable
      const savedThread1 = await repo.getById("id-1")
      const savedThread2 = await repo.getById("id-2")
      
      expect(savedThread1).not.toBeNull()
      expect(savedThread2).not.toBeNull()
      expect(savedThread1?.id).toBe("id-1")
      expect(savedThread2?.id).toBe("id-2")
      
      // Files should follow the name-id pattern
      const files = await fs.readdir(tmpDir)
      expect(files).toContain("same-name-id-1.yml")
      expect(files).toContain("same-name-id-2.yml")
      expect(files).toHaveLength(2)
    })
  })
  
  describe("error handling", () => {
    it("should handle corrupted YAML files when listing", async () => {
      // Create a corrupted file
      await fs.writeFile(
        path.join(tmpDir, "corrupted.yml"),
        "invalid: yaml: content:",
        "utf-8"
      )
      
      // Should list empty array
      const listed = await repo.listThreads()
      expect(listed).toHaveLength(0)
    })
    
    it("should throw ThreadRepositoryError on directory creation failure", async () => {
      // Create temp path
      const newTmpDir = path.join(os.tmpdir(), "file-ai-thread-error-test")
      
      // Create it as a file to cause mkdir to fail
      await fs.writeFile(newTmpDir, "", {mode: 0o444}) // read-only file
      
      const errorRepo = new FileAiThreadRepository(newTmpDir)
      await expect(errorRepo.listThreads())
        .rejects
        .toThrow(ThreadRepositoryError)
      
      // Clean up
      await fs.rm(newTmpDir, {force: true})
    })
  })
})