import { NestedHandler } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { LoadFileHandler, LoadFolderHandler } from '@coday/handlers/load'

export class LoadHandler extends NestedHandler {
  constructor(interactor: Interactor) {
    super(
      {
        commandWord: 'load',
        description: 'handles load related commands',
      },
      interactor
    )

    this.handlers = [new LoadFileHandler(interactor), new LoadFolderHandler(interactor)]
  }
}
