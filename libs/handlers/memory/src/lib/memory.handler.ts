import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'
import { MemoryCurateHandler, MemoryDeleteHandler, MemoryEditHandler, MemoryListHandler } from '@coday/handlers/memory'
import { NestedHandler } from '@coday/handler'

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
