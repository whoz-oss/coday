import {CommandContext} from "../command-context";
import {CommandHandler} from "./command-handler";
import {writeFileSync} from "node:fs";
import {Interactor} from "../interactor";

export class SaveHandler extends CommandHandler {
    commandWord: string = "save"
    description: string = "writes the context in a json file"

    constructor(private interactor: Interactor, private codayPath: string) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        let key: string
        if (!context?.task?.key) {
            key = this.interactor.promptText("No task defined yet, enter a title")
        } else {
            key = this.interactor.promptText(
                `Suggested name : '${context.task.key}', type new name or leave empty to accept suggestion`
                ,  context.task.key
            )
        }
        if (!key) {
            this.interactor.warn("Invalid save name, context not saved.")
        } else {
            const contextString = JSON.stringify(context, null, 2)
            const jsonFilePath = `${this.codayPath}/${key}`
            writeFileSync(jsonFilePath, contextString, 'utf8');
            this.interactor.displayText(`Context saved under: ${jsonFilePath}`)
        }
        return context
    }

}