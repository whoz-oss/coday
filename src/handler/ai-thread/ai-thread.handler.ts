import {Interactor, NestedHandler} from "../../model"
import {ListAiThreadHandler} from "./list-ai-thread.handler"
import {SelectAiThreadHandler} from "./select-ai-thread.handler"
import {SaveAiThreadHandler} from "./save-ai-thread.handler"
import {DeleteAiThreadHandler} from "./delete-ai-thread.handler"
import {AiThreadService} from "../../ai-thread/ai-thread.service"
import {NewAiThreadHandler} from "./new-ai-thread.handler"

/**
 * Nested handler managing AI thread operations through dedicated sub-handlers.
 * Provides thread-related commands like list, select, save, and delete.
 */
export class AiThreadHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    threadService: AiThreadService
  ) {
    super({
      commandWord: "thread",
      description: "Handles AI thread related commands (list, select, save, delete)"
    }, interactor)
    
    this.handlers = [
      new NewAiThreadHandler(interactor, threadService),
      new ListAiThreadHandler(interactor, threadService),
      new SelectAiThreadHandler(interactor, threadService),
      new SaveAiThreadHandler(interactor, threadService),
      new DeleteAiThreadHandler(interactor, threadService)
    ]
  }
}