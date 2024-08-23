import {CommandContext, CommandHandler, Interactor, PromptChain} from "../model"

const PROMPT_KEYWORD = "PROMPT"

export class PromptChainHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private promptChain: PromptChain,
    commandWord: string,
  ) {
    super({
      commandWord,
      description: promptChain.description,
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
