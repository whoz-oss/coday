import { Project } from './project'
import { AiThread } from '../ai-thread/ai-thread'

export class CommandContext {
  private commandQueue: string[] = []
  /**
   * Counter of remaining subTask slots "available"
   * Purpose is to limit the abuse and constrain the use for a start
   * @private
   */
  private subTaskCount: number = -1

  /**
   * Precise if the process is to end itself upon completion or ask the user for another
   */
  oneshot: boolean = false

  /**
   * Depth of the stack of threads for delegation
   */
  stackDepth: number = 0

  /**
   * Garbage object for each handling implementation to add specific data
   * Preferably organized by handler/client/class
   */
  data: any = {}

  /**
   * Instance of the AiThread currently selected
   */
  aiThread?: AiThread

  constructor(
    readonly project: Project,
    readonly username: string
  ) {}

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

  cloneWithoutCommands(): CommandContext {
    const clone = new CommandContext(this.project, this.username)
    clone.setSubTask(this.subTaskCount)
    clone.oneshot = this.oneshot
    clone.stackDepth = this.stackDepth
    clone.data = this.data
    return clone
  }
}
