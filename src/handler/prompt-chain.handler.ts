import {Interactor} from "../model/interactor"
import {CommandContext} from "../model/command-context"
import {PromptChain} from "../model/project-description"
import {CommandHandler} from "../model/command.handler"

const PROMPT_KEYWORD = "PROMPT"

export class PromptChainHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private promptChain: PromptChain,
    commandWord: string,
    description: string
  ) {
    super({
      commandWord,
      description,
      requiredIntegrations: promptChain.requiredIntegrations || []
    })
  }
  
  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const userPrompt = this.getSubCommand(command)
    const mappedCommands = this.promptChain.commands.map(c => c.replaceAll(PROMPT_KEYWORD, userPrompt))
    context.addCommands(...mappedCommands)
    return context
  }
}
