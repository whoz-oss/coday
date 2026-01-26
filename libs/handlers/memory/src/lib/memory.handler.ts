import { Interactor } from '@coday/model'
import { CodayServices } from '@coday/coday-services'
import { NestedHandler } from '@coday/handler'
import { MemoryEditHandler } from './edit.handler'
import { MemoryListHandler } from './list.handler'
import { MemoryDeleteHandler } from './delete.handler'
import { MemoryCurateHandler } from './curate.handler'

export class MemoryHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super(
      {
        commandWord: 'memory',
        description: 'memory management commands',
        isInternal: true,
      },
      interactor
    )

    this.handlers = [
      new MemoryListHandler(interactor, services.memory),
      new MemoryEditHandler(interactor, services.memory),
      new MemoryDeleteHandler(interactor, services.memory),
      new MemoryCurateHandler(interactor),
    ]
  }
}
