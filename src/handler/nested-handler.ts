import {CommandHandler} from "./command-handler";
import {CommandContext} from "../command-context";
import {Interactor} from "../interactor";

export abstract class NestedHandler extends CommandHandler {
    handlers: CommandHandler[] = []

    protected constructor(protected readonly interactor: Interactor) {
        super()
    }

    override accept(command: string, context: CommandContext): boolean {
        // quick skip if command word is not even correct
        if (!super.accept(command, context)) {
            return false
        }

        const subCommand = this.getSubCommand(command)

        // accept only if a sub-handler accepts or if no subCommand
        return !subCommand || !!this.handlers.find((h) => h.accept(subCommand, context))
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const subCommand = this.getSubCommand(command)

        if (this.isHelpAsked(subCommand)) {
            return context
        }

        // find first handler
        const handler = this.handlers.find((h: CommandHandler) => h.accept(subCommand, context))

        if (handler) {
            return handler.handle(subCommand, context)
        }

        // no handler found...
        this.interactor.warn(`No handler found in ${this.commandWord} handler`)
        return context
    }

    isHelpAsked(command?: string): boolean {
        const help = !command || command === "help" || command === "h"
        if (help) {
            this.interactor.displayText("Available commands:")
            this.interactor.displayText("  - help/h : displays this help message")
            this.handlers.forEach(h => this.interactor.displayText(`  - ${h.commandWord} : ${h.description}`))
        }
        return help
    }
}