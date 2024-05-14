import {CommandContext} from "./command-context";
import {CommandHandler} from "./command-handler";
import * as readlineSync from "readline-sync";
import {writeFileSync} from "node:fs";

export class SaveHandler extends CommandHandler {
    commandWord: string = "save"
    description: string = "writes the context in a json file"

    constructor(private codayPath: string) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        let key: string
        if (!context?.task?.key) {
            key = readlineSync.question("No task defined yet, enter a title:", {})
        } else {
            key = readlineSync.question(
                `Suggested name : '${context.task.key}', type new name or leave empty to accept suggestion:`
                , {defaultInput: context.task.key}
            )
        }
        if (!key) {
            console.warn("Invalid save name, context not saved.")
        } else {
            const contextString = JSON.stringify(context, null, 2)
            const jsonFilePath = `${this.codayPath}/${key}`
            writeFileSync(jsonFilePath, contextString, 'utf8');
            console.log("Context saved under: ", jsonFilePath)
        }
        return context
    }

}