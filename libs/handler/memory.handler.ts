import { NestedHandler } from '../model/nested.handler'
import { Interactor } from '../model'
import { CodayServices } from '../coday-services'
import { MemoryListHandler } from './memory/list.handler'
import { MemoryDeleteHandler } from './memory/delete.handler'
import { MemoryEditHandler } from './memory/edit.handler'
import { MemoryCurateHandler } from './memory/curate.handler'

export class MemoryHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    services: CodayServices
  ) {
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
      new MemoryCurateHandler(interactor)
    ]
  }
}