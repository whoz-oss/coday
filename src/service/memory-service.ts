import {Memory, MemoryLevel} from "../model/memory"

class MemoryService {
  private memories: Memory[] = []
  
  addMemory(memory: Partial<Memory>): void {
    if (this.memories.find(m => m.title === memory.title)) {
      throw new Error(`Memory with title '${memory.title}' already exists`)
    }
    const newMemory = new Memory(memory)
    this.memories.push(newMemory)
  }
  
  editMemory(memory: Partial<Memory>): void {
    if (!memory.title) {
      throw new Error(`Memory without title cannot be edited`)
    }
    const index = this.memories.findIndex(memory => memory.title === memory.title)
    if (index !== -1) {
      const memoryToUpdate = this.memories[index]
      memoryToUpdate.update(memory)
      // TODO: handle real save later just here
    } else {
      throw new Error(`Memory with title '${memory.title}' does not exist`)
    }
  }
  
  deleteMemory(title: string): void {
    const index = this.memories.findIndex(memory => memory.title === title)
    if (index !== -1) {
      this.memories.splice(index, 1)
    } else {
      throw new Error(`Memory with title '${title}' does not exist`)
    }
  }
  
  listMemories(level?: MemoryLevel): Memory[] {
    return !level ? this.memories : this.memories.filter(m => m.level === level)
  }
}

export const memoryService = new MemoryService()