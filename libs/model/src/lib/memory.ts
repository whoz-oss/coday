export class Memory {
  /**
   * For now, the title IS the identifier
   */
  title: string
  content: string
  level: MemoryLevel
  agentName?: string // TODO: not used yet !!!
  createdAt: Date
  updatedAt: Date | undefined

  constructor(m: Partial<Memory>) {
    this.title = m.title ?? 'no title'
    this.content = m.content ?? 'no content'
    this.level = m.level ?? MemoryLevel.PROJECT
    this.agentName = m.agentName
    this.createdAt = m.createdAt ?? new Date()
    this.updatedAt = m.updatedAt
  }

  update(m: Partial<Memory>): Memory {
    this.title = m.title ?? this.title
    this.content = m.content ?? this.content
    this.level = m.level ?? this.level
    this.agentName = m.agentName
    this.createdAt = m.createdAt ?? new Date()
    this.updatedAt = m.updatedAt ?? new Date()
    return this
  }
}

export enum MemoryLevel {
  USER = 'USER',
  PROJECT = 'PROJECT',
}
