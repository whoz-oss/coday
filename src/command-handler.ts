import {CommandContext} from "./command-context";


export abstract class CommandHandler {
    abstract commandWord: string
    abstract description: string
    abstract accept(command: string, context: CommandContext): boolean

    abstract handle(command: string, context: CommandContext): Promise<CommandContext>
}