import * as readlineSync from 'readline-sync';
import {handlers} from "./src/handlers";
import {CommandContext} from "./src/command-context";
import os from 'os';
import {existsSync, mkdirSync, writeFileSync} from "node:fs";
import {SaveHandler} from "./src/save-handler";

const PROJECT_ROOT: string = '/Users/vincent.audibert/Workspace/biznet.io/app/whoz'
const DATA_PATH: string = "/.coday/"

class MainHandler {

    context: CommandContext
    codayPath: string

    constructor() {
        const userInfo = os.userInfo()
        this.context = {
            projectRootPath: PROJECT_ROOT,
            username: userInfo.username
        }
        this.codayPath = `${userInfo.homedir}${DATA_PATH}`

        try {
            if (!existsSync(this.codayPath)) {
                mkdirSync(this.codayPath, { recursive: true });
                console.log(`Coday data folder created at: ${this.codayPath}`);
            } else {
                console.log(`Coday data folder used: ${this.codayPath}`);
            }
        } catch (error) {
            console.error(`Error creating directory:`, error);
        }
        handlers.push(new SaveHandler(this.codayPath))
    }

    async run(): Promise<void> {
        do {
            const command = readlineSync.question(`${this.context.username} : `)

            const handler = handlers.find(h => h.accept(command, this.context))

            if (handler) {
                this.context = await handler.handle(command, this.context)
            } else if (command === "exit") {
                break;
            } else {
                console.log("Command not understood, available commands:")
                handlers.forEach(h => console.log(`  - ${h.commandWord} : ${h.description}`))
            }

        } while (true)
    }
}

new MainHandler().run()