import * as readlineSync from 'readline-sync';
import {handlers} from "./src/handlers";
import {CommandContext} from "./src/command-context";
import os from 'os';
import {existsSync, mkdirSync, readdirSync} from "node:fs";
import {SaveHandler} from "./src/save-handler";
import {readFileSync} from "fs";
import {LoadHandler} from "./src/load-handler";

const PROJECT_ROOT: string = '/Users/vincent.audibert/Workspace/biznet.io/app/whoz'
const DATA_PATH: string = "/.coday/"

class MainHandler {

    codayPath: string
    userInfo: os.UserInfo<string>
    loadHandler: LoadHandler

    constructor() {
        this.userInfo = os.userInfo()
        this.codayPath = this.initCodayPath(this.userInfo)
        this.loadHandler = new LoadHandler(this.codayPath, PROJECT_ROOT, this.userInfo.username)
        handlers.push(new SaveHandler(this.codayPath))
        handlers.push(this.loadHandler)
    }

    async run(): Promise<void> {
        let context: CommandContext | null = null

        do {
            if (!context) {
                context = await this.loadHandler.handle("load", this.loadHandler.defaultContext)
            }

            // allow user input
            console.log("")
            const command = readlineSync.question(`${context.username} : `)

            // find first handler
            const handler = handlers.find(h => h.accept(command, context!))

            if (handler) {
                context = await handler.handle(command, context)
            } else if (command === "exit") {
                break;
            } else if (!command || command === "help" || command === "h") {
                console.log("Available commands:")
                console.log("  - help : displays this help message")
                handlers.forEach(h => console.log(`  - ${h.commandWord} : ${h.description}`))
                console.log("  - [any other text] : asks ChatGPT with the current context.")
            } else {
                console.log("ChatGPT part not yet ready...")
            }

        } while (true)
    }

    private initCodayPath(userInfo: os.UserInfo<string>): string {
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