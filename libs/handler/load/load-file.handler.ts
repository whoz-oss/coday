import { CommandContext, CommandHandler, Interactor } from '../../model'
import { readFileByPath } from '../../function/read-file-by-path'

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

    const content = readFileByPath({
      relPath: filePath,
      root: context.project.root,
      interactor: this.interactor,
    })
    context.aiThread?.addUserMessage(context.username, `File with path: ${filePath}\n\n${content}`)

    return context
  }
}
