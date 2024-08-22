import {Memory, MemoryLevel} from "../model/memory"
import path from "path"
import {existsSync, writeFileSync} from "node:fs"
import * as yaml from "yaml"
import {readFileSync} from "fs"

const MEMORY_FILE_NAME: string = "memories.yaml"

class MemoryService {
  private memories: Memory[] = []
  private userFolderPath: string | undefined
  private projectFolderPath: string | undefined
  
  setPaths(userFolderPath: string, projectFolderPath: string): void {
    this.userFolderPath = this.readMemories(userFolderPath) ? userFolderPath : undefined
    this.projectFolderPath = this.readMemories(projectFolderPath) ? projectFolderPath : undefined
  }
  
  private readMemories(folderPath: string): boolean {
    try {
      const memoryPath = path.join(folderPath, MEMORY_FILE_NAME)
      if (!existsSync(memoryPath)) {
        const emptyContent = yaml.stringify({memories: []})
        writeFileSync(memoryPath, emptyContent)
      }
      const content = yaml.parse(readFileSync(memoryPath, "utf-8")) as { memories: Memory[] }
      this.memories.push(...content.memories)
      return true
    } catch (_: any) {
      return false
    }
  }
  
  private writeMemories(folderPath: string, memories: Memory[]): void {
    const memoryPath = path.join(folderPath, MEMORY_FILE_NAME)
    const content = yaml.stringify({memories})
    writeFileSync(memoryPath, content)
  }
  
  private checkInit(): void {
    if (!this.userFolderPath || !this.projectFolderPath) {
      throw new Error("user or project path not set for memory service")
    }
  }
  
  upsertMemory(memory: Partial<Memory>): void {
    this.checkInit()
    const index = this.memories.findIndex(m => m.title === memory.title)
    
    if (index === -1) {
      // insert
      this.memories.push(new Memory(memory))
    } else {
      // update
      this.memories[index] = new Memory({...this.memories[index], ...memory})
    }
    this.saveMemories()
  }
  
  private saveMemories(): void {
    const userMemories = this.memories.filter(m => m.level === MemoryLevel.USER)
    const projectMemories = this.memories.filter(m => m.level === MemoryLevel.PROJECT)
    this.writeMemories(this.userFolderPath!!, userMemories)
    this.writeMemories(this.projectFolderPath!!, projectMemories)
  }
  
  deleteMemory(title: string): void {
    this.checkInit()
    const index = this.memories.findIndex(memory => memory.title === title)
    if (index !== -1) {
      this.memories.splice(index, 1)
      this.saveMemories()
    } else {
      throw new Error(`Memory with title '${title}' does not exist`)
    }
  }
  
  listMemories(level?: MemoryLevel): Memory[] {
    this.checkInit()
    return !level ? this.memories : this.memories.filter(m => m.level === level)
  }
}

export const memoryService = new MemoryService()