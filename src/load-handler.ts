import {CommandHandler} from "./command-handler";
import {CommandContext} from "./command-context";
import * as readlineSync from "readline-sync";
import {readdirSync, writeFileSync} from "node:fs";
import {readFileSync} from "fs";

export class LoadHandler extends CommandHandler {
    commandWord: string = "load"
    description: string = "load the context from a json file or create a new context"

    defaultContext: CommandContext
    constructor(private codayPath: string, private projectRoot: string, private username: string) {
        super()
        this.defaultContext = {
            projectRootPath: this.projectRoot,
            username: this.username,
            commandQueue: [],
            history: []
        }
    }

    accept(command: string, context: CommandContext): boolean {
        return command.trim() === this.commandWord
    }

    async handle(command: string, currentContext: CommandContext): Promise<CommandContext> {
        let context: CommandContext | null = null
        const files = readdirSync(this.codayPath)
        if (files.length) {
            console.log("Select a context file:")
            for (let i = 0; i < files.length; i++) {
                console.log(`  ${i + 1} - ${files[i]}`) // make numbers start from 1 for the user
            }
            const selection = readlineSync.question("Type file number (a=abort, empty=new): ")
            if (selection === "a") {
                context = currentContext
            } else if (!!selection) {
                const index = parseInt(selection)-1 // back to start from 0
                const path = `${this.codayPath}/${files[index]}`
                const data = readFileSync(path, 'utf-8')
                context = JSON.parse(data)
            }
        }
        if (!context) {
            console.log("Defaulting on new context")
            context = this.defaultContext
        }
        return context
    }

}