import { Memory, MemoryLevel } from '@coday/model'
import * as path from 'node:path'
import { existsSync, writeFileSync } from 'node:fs'
import * as yaml from 'yaml'
import { UserService } from './user.service'
import { ProjectStateService } from './project-state.service'
import { distinctUntilChanged } from 'rxjs'
import { readYamlFile, writeYamlFile } from '@coday/utils'

/**
 * Name of the memories file, could be in different folders depending on the level
 */
const MEMORY_FILE_NAME: string = 'memories.yaml'

export class MemoryService {
  private memories: Memory[] = []
  private userMemoriesPath: string | undefined
  private projectMemoriesPath: string | undefined

  constructor(projectService: ProjectStateService, userService: UserService) {
    this.userMemoriesPath = path.join(userService.userConfigPath, MEMORY_FILE_NAME)
    projectService.selectedProject$.pipe(distinctUntilChanged()).subscribe((selectedProject) => {
      this.projectMemoriesPath = selectedProject?.configPath
        ? path.join(selectedProject.configPath, MEMORY_FILE_NAME)
        : undefined
      if (selectedProject) this.loadMemoriesFrom(selectedProject)
    })
  }

  upsertMemory(memory: Partial<Memory>): void {
    this.checkInit()
    const index = this.memories.findIndex((m) => m.title === memory.title)

    if (index === -1) {
      // insert
      this.memories.push(new Memory(memory))
    } else {
      // update
      // TODO: do not merge if different agentName ?
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

  listMemories(level: MemoryLevel, agentName?: string): Memory[] {
    this.checkInit()
    return this.memories.filter((m) => m.level === level && (!m.agentName || m.agentName === agentName))
  }

  getFormattedMemories(level: MemoryLevel, agentName?: string): string {
    const levelMemories = this.listMemories(level, agentName).map((m: Memory) => `  - ${m.title}\n    ${m.content}`)
    if (!levelMemories.length) return ''

    levelMemories.unshift(`## ${level} memories

    Here are the information collected during previous chats:\n`)
    return levelMemories.join('\n')
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
