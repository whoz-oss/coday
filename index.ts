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
        let context: CommandContext | null = null

        // Main loop to keep waiting for user input
        do {
            if (!context) {
                context = await this.loadHandler.handle("load", this.loadHandler.defaultContext)
            }

            // allow user input
            console.log("")
            const userCommand = readlineSync.question(`${context.username} : `)

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
            context.commandQueue.push(userCommand)

            let count = 0
            while (context && context.commandQueue.length > 0 && count < MAX_ITERATIONS) {
                count++
                const command: string | undefined = context.commandQueue.shift()
                if (!command) {
                    continue;
                }

                // find first handler
                const handler: CommandHandler | undefined = handlers.find(h => h.accept(command, context!))

                try {
                    // try handlers in their preference order
                    if (handler) {
                        context = await handler.handle(command, context)
                    }  else {
                        // default case: repackage the command as an open question for AI
                        context.commandQueue.unshift(`${this.openaiHandler.commandWord} ${command}`)
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