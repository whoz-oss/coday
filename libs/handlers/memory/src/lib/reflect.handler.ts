import * as fs from 'fs/promises'
import * as path from 'path'
import * as os from 'os'
import { fileURLToPath } from 'url'
import { CommandHandler } from '@coday/handler'
import { CommandContext } from '@coday/model'
import { Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { MessageEvent } from '@coday/model'

export class MemoryReflectHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'reflect',
      description: 'Select a past thread and have the AI analyze it to extract learnings.',
    })
  }

  async handle(_command: string, context: CommandContext): Promise<CommandContext> {
    const projectName = context.project.name
    const username = context.username

    const threads = await this.services.thread.listThreads(projectName, username)

    if (!threads.length) {
      this.interactor.displayText('No threads found for this project.')
      return context
    }

    const sorted = [...threads].sort((a, b) => (a.modifiedDate > b.modifiedDate ? -1 : 1))
    const options = sorted.map((t) => `[${t.modifiedDate}] ${t.name}`)

    const chosen = await this.interactor.chooseOption(options, 'Select a thread to reflect on:')
    if (!chosen) return context

    const selectedIndex = options.indexOf(chosen)
    const selectedThread = sorted[selectedIndex]
    if (!selectedThread) return context

    const thread = await this.services.thread.getThread(projectName, selectedThread.id)
    if (!thread) {
      this.interactor.displayText('Thread not found.')
      return context
    }

    const messages = thread.getAllMessages()
    const formattedMessages = messages
      .filter((m): m is MessageEvent => m instanceof MessageEvent)
      .map((m) => `${m.role} (${m.name}):\n${m.getTextContent()}`)
      .join('\n\n')

    const reflectPrompt = await this.readReflectPrompt()
    const combined = reflectPrompt + '\n\n## Conversation to analyze\n\n' + formattedMessages

    context.addCommands('ai ' + combined)
    return context
  }

  private async readReflectPrompt(): Promise<string> {
    const userFilePath = path.join(os.homedir(), '.coday', 'reflect-prompt.md')
    try {
      return await fs.readFile(userFilePath, 'utf-8')
    } catch {
      // fall back to default
    }
    const currentDir = path.dirname(fileURLToPath(import.meta.url))
    const defaultFilePath = path.join(
      currentDir,
      '..',
      '..',
      '..',
      '..',
      'agent',
      'src',
      'lib',
      'default-reflect-prompt.md'
    )
    try {
      return await fs.readFile(defaultFilePath, 'utf-8')
    } catch {
      return 'Analyze the conversation below and extract learnings worth memorizing. Ask for confirmation before memorizing each one.'
    }
  }
}
