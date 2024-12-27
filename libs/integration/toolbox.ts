import { CommandContext, Interactor } from '../model'
import { AiTools } from './ai/ai.tools'
import { FileTools } from './file/file.tools'
import { JiraTools } from './jira/jira.tools'
import { GitTools } from './git/git.tools'
import { ScriptsTools } from './scripts.tools'
import { GitLabTools } from './gitlab/gitlab.tools'
import { MemoryTools } from './memory.tools'
import { AssistantToolFactory, CodayTool } from './assistant-tool-factory'
import { ConfluenceTools } from './confluence/confluence.tools'
import {AsyncAssistantToolFactory} from "./async-assistant-tool-factory";
import {CodayServices} from "../coday-services";

export class Toolbox {
    private toolFactories: AssistantToolFactory[]
    private asyncToolFactories: AsyncAssistantToolFactory[]
    private tools: CodayTool[] = []

  constructor(interactor: Interactor, services: CodayServices) {
    this.toolFactories = [
      new AiTools(interactor),
      new FileTools(interactor),
      new GitTools(interactor, services.integration),
      new ScriptsTools(interactor),
      new GitLabTools(interactor, services.integration),
      new MemoryTools(interactor, services.memory),
      new ConfluenceTools(interactor, services.integration),
    ]
      this.asyncToolFactories = [new JiraTools(interactor, services.integration)]

  }

    getTools(context: CommandContext): CodayTool[] {
        this.tools = this.toolFactories.flatMap((factory) => factory.getTools(context))
        return this.tools
    }

    async getAsyncTools(context: CommandContext): Promise<CodayTool[]> {
        const asyncTools = await Promise.all(
            this.asyncToolFactories.map((asyncFactory) => asyncFactory.getAsyncTools(context))
        )
        this.tools = asyncTools.flat()
        return this.tools
    }
}
