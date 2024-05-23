import {CommandContext} from "../command-context"
import {CommandHandler} from "./command-handler"
import {Interactor} from "../interactor"
import {OpenaiClient} from "./openai-client";

export class OpenaiHandler extends CommandHandler {
    commandWord: string = 'ai'
    description: string = 'calls the AI with the given command and current context'
    openaiClient: OpenaiClient

    constructor(interactor: Interactor) {
        super()
        this.openaiClient = new OpenaiClient(interactor)
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const cmd = this.getSubCommand(command)
        let fullTextAnswer: string
        try {
            fullTextAnswer = await this.openaiClient.answer(cmd, context)
        } catch (error: any) {
            console.error(error)
            fullTextAnswer = error.toString()
        }
        return {...context, history: [...context.history, {command, response: fullTextAnswer}]}
    }
}
