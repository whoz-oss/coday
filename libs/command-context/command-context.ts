import { Project } from './project'
import { AiThread } from '@coday/ai-thread'

export class CommandContext {
  private commandQueue: string[] = []

  /**
   * Clears all pending commands from the queue.
   * Should be called when processing is stopped to avoid
   * executing commands with invalid context.
   */
  clearCommands(): void {
    this.commandQueue = []
  }

  /**
   * Precise if the process is to end itself upon completion or ask the user for another
   */
  oneshot: boolean = false

  /**
   * When true, prevent any file write or delete operations
   */
  fileReadOnly: boolean = false

  /**
   * Root directory for thread-specific files (conversation workspace)
   * Format: .coday/projects/{projectName}/threads/{threadId}-files
   */
  threadFilesRoot?: string

  /**
   * Depth of the stack of threads for delegation
   */
  stackDepth: number = 3

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

  getSubContext(...commands: string[]): CommandContext {
    const subContext = new CommandContext(this.project, this.username)
    subContext.oneshot = this.oneshot
    subContext.fileReadOnly = this.fileReadOnly
    subContext.addCommands(...commands)
    subContext.stackDepth = this.stackDepth - 1
    return subContext
  }

  cloneWithoutCommands(): CommandContext {
    const clone = new CommandContext(this.project, this.username)
    clone.oneshot = this.oneshot
    clone.fileReadOnly = this.fileReadOnly
    clone.stackDepth = this.stackDepth
    clone.data = this.data
    return clone
  }
}
