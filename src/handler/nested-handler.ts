import {CommandHandler} from "./command-handler";
import {CommandContext} from "../command-context";

export abstract class NestedHandler extends CommandHandler {
    handlers: CommandHandler[] = []

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const subCommand = command.slice(this.commandWord.length).trim()

        // find first handler
        const handler = this.handlers.find((h: CommandHandler) => h.accept(subCommand, context))

        if (handler) {
            return handler.handle(subCommand, context)
        }

        // no handler found...
        console.warn(`No handler found in ${this.commandWord} handler`)
        return context
    }
}