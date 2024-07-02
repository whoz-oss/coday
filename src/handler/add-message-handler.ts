import {CommandHandler} from "./command-handler";
import {Interactor} from "../interactor";
import { CommandContext } from "../command-context";
import {OpenaiClient} from "./openai-client";

export class AddMessageHandler extends CommandHandler {
    commandWord = 'add-message'
    description = '[internal] used to allow user feedback between flow commands.'

    constructor(private interactor: Interactor, private openaiClient: OpenaiClient) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const msg = this.getSubCommand(command)
        const invite = msg || 'What message would you want to add ?'
        const userAnswer = this.interactor.promptText(`${invite}\n(type nothing to proceed) `)

        if (userAnswer) {
            await this.openaiClient.addMessage(userAnswer)
        }

        return context
    }
}