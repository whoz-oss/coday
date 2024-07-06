import {Project} from "./project"

export class CommandContext {
  private commandQueue: string[] = []
  private subTaskCount: number = 0
  oneshot: boolean = false
  
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
}
