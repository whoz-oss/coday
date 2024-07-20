import {Interactor} from "../../model/interactor"
import {ListThreadHandler} from "./list-thread.handler"
import {SelectThreadHandler} from "./select-thread.handler"
import {SaveThreadHandler} from "./save-thread.handler"
import {DeleteThreadHandler} from "./delete-thread.handler"
import {LoadThreadHandler} from "./load-thread.handler"
import {NestedHandler} from "../../model/nested.handler"
import {OpenaiClient} from "../openai-client"

export class ThreadHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "thread",
      description: "handles thread related commands"
    }, interactor)
    
    this.handlers = [
      new ListThreadHandler(interactor, openaiClient),
      new SelectThreadHandler(interactor, openaiClient),
      new SaveThreadHandler(interactor, openaiClient),
      new DeleteThreadHandler(interactor, openaiClient),
      new LoadThreadHandler(interactor, openaiClient)
    ]
  }
}
