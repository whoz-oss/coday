import {LoadFileHandler} from "./load-file.handler"
import {LoadFolderHandler} from "./load-folder.handler"
import {Interactor, NestedHandler} from "../../model"

export class LoadHandler extends NestedHandler {
  constructor(
    interactor: Interactor,
  ) {
    super({
      commandWord: "load",
      description: "handles load related commands"
    }, interactor)
    
    this.handlers = [
      new LoadFileHandler(interactor),
      new LoadFolderHandler(interactor)
    ]
  }
}