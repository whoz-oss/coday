import {LoadFileHandler} from "./load-file.handler"
import {LoadFolderHandler} from "./load-folder.handler"
import {Interactor, NestedHandler} from "../../model"
import {OpenaiClient} from "../openai-client"

export class LoadHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
    openaiClient: OpenaiClient
  ) {
    super({
      commandWord: "load",
      description: "handles load related commands"
    }, interactor)
    
    this.handlers = [
      new LoadFileHandler(interactor, openaiClient),
      new LoadFolderHandler(interactor)
    ]
  }
}