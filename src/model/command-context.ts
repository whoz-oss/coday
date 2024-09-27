import {Project} from "./project"

export class CommandContext {
  private commandQueue: string[] = []
  private subTaskCount: number = -1
  oneshot: boolean = false
  stackDepth: number = 1
  
  /**
   * Garbage object for each handling implementation to add specific data
   * Preferably organized by handler/client/class
   */
  data: any = {}
  
  constructor(
    readonly project: Project,
    readonly username: string,
  ) {
  }
  
  addCommands(...commands: string[]): void {
    this.commandQueue.unshift(...commands)
  }
  
  getFirstCommand(): string | undefined {
    return this.commandQueue.shift()
  }
  
  canSubTask(callback: () => void): boolean {
    const subTaskAvailable = this.subTaskCount !== 0
    if (subTaskAvailable) {
      callback()
    }
    return subTaskAvailable
  }
  
  addSubTasks(...commands: string[]): boolean {
    if (this.subTaskCount !== 0) {
      if (this.subTaskCount > 0) {
        this.subTaskCount--
      }
      this.addCommands(...commands)
      return true
    }
    return false
  }
  
  setSubTask(value: number): void {
    this.subTaskCount = value
  }
  
  getSubContext(...commands: string[]): CommandContext {
    const subContext = new CommandContext(this.project, this.username)
    subContext.setSubTask(this.subTaskCount)
    subContext.oneshot = this.oneshot
    subContext.addCommands(...commands)
    subContext.stackDepth = this.stackDepth - 1
    return subContext
  }
}
