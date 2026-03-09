import { AiThread } from './ai-thread'
import { Project } from './project'

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

  /**
   * Stack of threads for delegation depth tracking.
   * When a delegation runs, the sub-thread is pushed onto this stack.
   * The delegate tool reads from the top of the stack (or falls back to aiThread)
   * to correctly resolve the "current" thread at any delegation depth.
   */
  private threadStack: AiThread[] = []

  /**
   * Returns the thread at the current delegation depth.
   * If a delegation is running, returns the sub-thread on top of the stack.
   * Otherwise, returns the root aiThread.
   */
  get currentThread(): AiThread | undefined {
    return this.threadStack.length > 0 ? this.threadStack[this.threadStack.length - 1] : this.aiThread
  }

  /**
   * Push a sub-thread onto the stack before running a delegated agent.
   * Must be paired with popThread() after the delegation completes.
   */
  pushThread(thread: AiThread): void {
    this.threadStack.push(thread)
  }

  /**
   * Pop the sub-thread from the stack after a delegation completes.
   * Returns the popped thread, or undefined if the stack was empty.
   */
  popThread(): AiThread | undefined {
    return this.threadStack.pop()
  }

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
