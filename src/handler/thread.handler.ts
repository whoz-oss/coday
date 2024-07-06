import {CommandHandler} from "./command.handler"
import {CommandContext} from "../model/command-context"
import {Interactor} from "../model/interactor"
import {OpenaiClient} from "./openai-client"
import {Thread} from "../model/thread"
import {threadService} from "../service/thread.service"

export class ThreadHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "thread",
      description: "handles thread related commands"
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const cmd = this.getSubCommand(command)
    let result: CommandContext | null = context
    
    if (!cmd) {
      this.interactor.displayText(
        `${this.commandWord} can accept sub-commands: save, list, select, delete.`,
      )
    }
    if (cmd === "save") {
      await this.handleSaveThread()
    }
    if (cmd === "list") {
      const threads = threadService.listThreads()
      if (!threads?.length) {
        this.interactor.displayText("No thread saved.")
      } else {
        const currentThreadId = this.openaiClient.threadId
        this.interactor.displayText(`Saved threads:`)
        threads
          .map(t => `  - ${this.formatThread(t.threadId, t.name, currentThreadId)}`)
          .forEach(text => this.interactor.displayText(text))
      }
    }
    if (cmd === "select") {
      const selectedThread = await this.selectThread("List of saved threads:")
      this.openaiClient.threadId = selectedThread?.threadId ?? null
    }
    if (cmd === "delete") {
      const selectedThread = await this.selectThread("Select the thread to delete:")
      this.openaiClient.threadId = null
      threadService.deleteThread(selectedThread?.threadId)
      this.interactor.displayText(`Deleted thread: ${selectedThread?.threadId}`)
    }
    
    if (!result) {
      throw new Error("Context lost in the process")
    }
    return result
  }
  
  async handleSaveThread() {
    const threadId = this.openaiClient.threadId
    if (!threadId) {
      this.interactor.warn("Currently no thread started, nothing to save.")
      return
    }
    const name = await this.interactor.promptText("Thread title :")
    threadService.saveThread(threadId, name)
  }
  
  private formatThread(threadId: string, name: string, currentThreadId: string | null): string {
    return `${threadId} : ${currentThreadId === threadId ? "[CURRENT] " : ""}${name}`
  }
  
  private async selectThread(invite: string): Promise<Thread | undefined> {
    const threads = threadService.listThreads()
    const currentThreadId = this.openaiClient.threadId
    const threadsByText = new Map<string, Thread>()
    threads.forEach(t => threadsByText.set(this.formatThread(t.threadId, t.name, currentThreadId), t))
    const options = Array.from(threadsByText.keys())
    const selected = await this.interactor.chooseOption(options, "Selection", invite)
    return threadsByText.get(selected)
  }
}
