import { CommandHandler } from '@coday/handler'
import { CommandContext } from '@coday/model'
import { Interactor } from '@coday/model'
import { readFileUnifiedAsString } from '@coday/function'

export class LoadFileHandler extends CommandHandler {
  constructor(private interactor: Interactor) {
    super({
      commandWord: 'file',
      description: 'Loads a file by its path relative to project root, ex: `load-file ./folder/file.extension`',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const subCommand = this.getSubCommand(command)
    let filePath = subCommand.trim()

    if (!filePath) {
      this.interactor.error('Please provide a valid file path.')
      return context
    }

    if (!filePath.startsWith('.')) {
      filePath = `.${filePath}`
    }

    const content = await readFileUnifiedAsString({
      relPath: filePath,
      root: context.project.root,
      interactor: this.interactor,
    })
    context.aiThread?.addUserMessage(context.username, {
      type: 'text',
      content: `File with path: ${filePath}\n\n${content}`,
    })

    return context
  }
}
