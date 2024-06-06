import {Context} from "../context"
import {CommandHandler} from "./command-handler"
import {Interactor} from "../interactor"
import {OpenaiClient} from "./openai-client"
import {configService} from "../service/config-service";

export class OpenaiHandler extends CommandHandler {
    commandWord: string = 'ai'
    description: string = "calls the AI with the given command and current context. 'reset' for using a new thread."
    openaiClient: OpenaiClient

    constructor(interactor: Interactor) {
        super()
        const apiKeyProvider = () => configService.getApiKey("OPENAI")
        this.openaiClient = new OpenaiClient(interactor, apiKeyProvider)
    }

    async handle(command: string, context: Context): Promise<Context> {
        const cmd = this.getSubCommand(command)

        // Reset threadId when command is "reset"
        if (cmd === "reset") {
            this.openaiClient.reset()
            return context
        }

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
