import {GitDefaultHandler} from "./git-default.handler"
import {GitStatusHandler} from "./git-status.handler"
import {NestedHandler} from "../../model/nested.handler"
import {Interactor} from "../../model/interactor"
import {IntegrationName} from "../../model/integration-name"

export class GitHandler extends NestedHandler {
  
  
  constructor(interactor: Interactor) {
    super({
      commandWord: "git",
      description: "handles git-related commands",
      requiredIntegrations: [IntegrationName.GIT]
    }, interactor)
    this.handlers = [
      new GitStatusHandler(interactor),
      new GitDefaultHandler(interactor) // IMPORTANT to keep in last position
    ]
  }
}
