import * as readlineSync from 'readline-sync';
import {handlers} from "./src/handlers";
import {CommandContext} from "./src/command-context";
import os from 'os';
import {existsSync, mkdirSync} from "node:fs";
import {SaveHandler} from "./src/save-handler";
import {LoadHandler} from "./src/load-handler";
import OpenAI from "openai";
import {CommandHandler} from "./src/command-handler";
import {OpenaiHandler} from "./src/openai-handler";

const PROJECT_ROOT: string = '/Users/vincent.audibert/Workspace/coday'
const DATA_PATH: string = "/.coday/"
const MAX_ITERATIONS: number = 10

class MainHandler {

    codayPath: string
    userInfo: os.UserInfo<string>
    loadHandler: LoadHandler
    openaiHandler: OpenaiHandler
    context: CommandContext | null = null

    constructor() {
        this.userInfo = os.userInfo()
        this.codayPath = this.initCodayPath(this.userInfo)
        this.loadHandler = new LoadHandler(this.codayPath, PROJECT_ROOT, this.userInfo.username)

        this.openaiHandler = new OpenaiHandler()

        handlers.push(new SaveHandler(this.codayPath))
        handlers.push(this.loadHandler)
        handlers.push(this.openaiHandler)
    }

    async run(): Promise<void> {

        // Main loop to keep waiting for user input
        do {
            // initiate context
            if (!this.context) {
                this.context = await this.loadHandler.handle("load", this.loadHandler.defaultContext)
            }

            // allow user input
            console.log("")
            const userCommand = readlineSync.question(`${this.context.username} : `)

            // quit loop if user wants to exit
            if (userCommand === "exit") {
                break;
            } else if (!userCommand || userCommand === "help" || userCommand === "h") {
                console.log("Available commands:")
                console.log("  - help : displays this help message")
                handlers.forEach(h => console.log(`  - ${h.commandWord} : ${h.description}`))
                console.log("  - [any other text] : defaults to asking the AI with the current context.")
            }

            // add the user command to the queue and let handlers decompose it in many and resolve them ultimately
            this.context.commandQueue.push(userCommand)

            let count = 0
            while (this.context && this.context.commandQueue.length > 0 && count < MAX_ITERATIONS) {
                count++
                const command: string | undefined = this.context.commandQueue.shift()
                if (!command) {
                    continue;
                }

                // find first handler
                const handler: CommandHandler | undefined = handlers.find((h: CommandHandler) => h.accept(command, this.context!))

                try {
                    // try handlers in their preference order
                    if (handler) {
                        this.context = await handler.handle(command, this.context)
                    }  else {
                        // default case: repackage the command as an open question for AI
                        this.context.commandQueue.unshift(`${this.openaiHandler.commandWord} ${command}`)
                    }
                } catch (error) {
                    console.error(`An error occurred while trying to process your request: ${error}`)
                }
            }
        } while (true)
    }

    private initCodayPath(userInfo: os.UserInfo<string>): string {
        // define data path to store settings and other
        const codayPath = `${userInfo.homedir}${DATA_PATH}`

        try {
            if (!existsSync(codayPath)) {
                mkdirSync(codayPath, {recursive: true});
                console.log(`Coday data folder created at: ${codayPath}`);
            } else {
                console.log(`Coday data folder used: ${codayPath}`);
            }
        } catch (error) {
            console.error(`Error creating directory:`, error);
        }
        return codayPath
    }
}

new MainHandler().run()