import {CommandHandler} from "./command-handler";
import {CommandContext} from "./command-context";
import {readdirSync} from "node:fs";
import {readFileSync} from "fs";
import {Interactor} from "./interactor";

export class LoadHandler extends CommandHandler {
    commandWord: string = "load"
    description: string = "load the context from a json file or create a new context"

    defaultContext: CommandContext

    constructor(
        private interactor: Interactor,
        private codayPath: string,
        private projectRoot: string,
        private username: string,
    ) {
        super()
        this.defaultContext = {
            projectRootPath: this.projectRoot,
            username: this.username,
            commandQueue: [],
            history: []
        }
    }

    async handle(command: string, currentContext: CommandContext): Promise<CommandContext> {
        let context: CommandContext | null = null
        const files = readdirSync(this.codayPath)
        if (files.length) {
            const selection = this.interactor.chooseOption(files, "Type file number (a=abort, empty=new): ", "Select a context file:")
            if (selection === "a") {
                context = currentContext
            } else if (!!selection) {
                const index = parseInt(selection) - 1 // back to start from 0
                const path = `${this.codayPath}/${files[index]}`
                const data = readFileSync(path, 'utf-8')
                context = JSON.parse(data)
            }
        }
        if (!context) {
            this.interactor.displayText("Defaulting on new context")
            context = this.defaultContext
        }
        return context
    }

}