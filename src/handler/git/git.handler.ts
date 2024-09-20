import {GitDefaultHandler} from "./git-default.handler"
import {GitStatusHandler} from "./git-status.handler"
import {Interactor, NestedHandler} from "../../model"

export class GitHandler extends NestedHandler {
  
  
  constructor(interactor: Interactor) {
    super({
      commandWord: "git",
      description: "handles git-related commands",
      requiredIntegrations: ["GIT"]
    }, interactor)
    this.handlers = [
      new GitStatusHandler(interactor),
      new GitDefaultHandler(interactor) // IMPORTANT to keep in last position
    ]
  }
}
