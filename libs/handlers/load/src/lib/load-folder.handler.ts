import { CommandContext, CommandHandler, Interactor } from '@coday/model'
import { listFilesAndDirectories } from '@coday/integration/file/list-files-and-directories'

export class LoadFolderHandler extends CommandHandler {
  constructor(private interactor: Interactor) {
    super({
      commandWord: 'folder',
      description: 'Loads files from a folder, does not do depth nor recursive.',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    let folderPath = this.getSubCommand(command)

    if (!folderPath) {
      this.interactor.error('Please provide a valid folder path.')
      return context
    }

    if (!folderPath.startsWith('.')) {
      folderPath = `.${folderPath}`
    }

    let entries: string[]
    try {
      entries = await listFilesAndDirectories({
        relPath: folderPath,
        root: context.project.root,
      })

      const fileCommands = entries
        .filter((entry: string) => !entry.endsWith('/')) // Filter out directories
        .map((file: string) => `load file ${folderPath}/${file}`)
      context.addCommands(...fileCommands)
      this.interactor.displayText(`Folder loaded: ${folderPath}`)
    } catch (error: any) {
      this.interactor.error(`Failed to load folder: ${error}`)
    }

    return context
  }
}
