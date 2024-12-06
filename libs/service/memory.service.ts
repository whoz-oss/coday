import { Memory, MemoryLevel } from '../model/memory'
import path from 'path'
import { existsSync, writeFileSync } from 'node:fs'
import * as yaml from 'yaml'
import { configService, ConfigService } from './config.service'
import { readYamlFile } from './read-yaml-file'
import { writeYamlFile } from './write-yaml-file'

/**
 * Name of the memories file, could be in different folders depending on the level
 */
const MEMORY_FILE_NAME: string = 'memories.yaml'

class MemoryService {
  private memories: Memory[] = []
  private userMemoriesPath: string | undefined
  private projectMemoriesPath: string | undefined

  constructor(configService: ConfigService) {
    this.userMemoriesPath = path.join(configService.configPath, MEMORY_FILE_NAME)
    configService.selectedProject$.subscribe((selectedProject) => this.loadMemoriesFrom(selectedProject))
  }

  upsertMemory(memory: Partial<Memory>): void {
    this.checkInit()
    const index = this.memories.findIndex((m) => m.title === memory.title)

    if (index === -1) {
      // insert
      this.memories.push(new Memory(memory))
    } else {
      // update
      this.memories[index] = new Memory({ ...this.memories[index], ...memory })
    }
    this.saveMemories()
  }

  deleteMemory(title: string): void {
    this.checkInit()
    const index = this.memories.findIndex((memory) => memory.title === title)
    if (index !== -1) {
      this.memories.splice(index, 1)
      this.saveMemories()
    } else {
      throw new Error(`Memory with title '${title}' does not exist`)
    }
  }

  listMemories(level?: MemoryLevel): Memory[] {
    this.checkInit()
    return !level ? this.memories : this.memories.filter((m) => m.level === level)
  }

  private loadMemoriesFrom(selectedProject: { configPath: string } | null): void {
    // got a new project selection
    // as a precaution, purge current memories
    this.memories = []

    // read user memories and reset if invalid
    this.userMemoriesPath = this.readMemories(this.userMemoriesPath) ? this.userMemoriesPath : undefined

    // read project memories and reset if invalid
    const candidatePath = selectedProject?.configPath
      ? path.join(selectedProject.configPath, MEMORY_FILE_NAME)
      : undefined
    this.projectMemoriesPath = this.readMemories(candidatePath) ? candidatePath : undefined
    if (selectedProject) {
      this.checkInit()
    }
  }

  private readMemories(memoryPath?: string): boolean {
    if (!memoryPath) {
      return false
    }
    try {
      if (!existsSync(memoryPath)) {
        const emptyContent = yaml.stringify({ memories: [] })
        writeFileSync(memoryPath, emptyContent)
      }
      const content = readYamlFile<{ memories: Memory[] }>(memoryPath)
      if (!content) {
        return false
      }
      this.memories.push(...content.memories)
      return true
    } catch (_: any) {
      return false
    }
  }

  private checkInit(): void {
    if (!this.userMemoriesPath || !this.projectMemoriesPath) {
      throw new Error('user or project path not set for memory service')
    }
  }

  private saveMemories(): void {
    this.checkInit()
    const userMemories = this.memories.filter((m) => m.level === MemoryLevel.USER)
    const projectMemories = this.memories.filter((m) => m.level === MemoryLevel.PROJECT)
    writeYamlFile(this.userMemoriesPath!!, { memories: userMemories })
    writeYamlFile(this.projectMemoriesPath!!, { memories: projectMemories })
  }
}

export const memoryService = new MemoryService(configService)
