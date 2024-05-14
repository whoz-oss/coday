import {CommandContext} from "./command-context";


export abstract class CommandHandler {
    abstract commandWord: string
    abstract description: string
    accept(command: string, context: CommandContext): boolean {
        return !!command && command.toLowerCase().startsWith(this.commandWord)
    }

    abstract handle(command: string, context: CommandContext): Promise<CommandContext>
}