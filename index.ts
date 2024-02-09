import * as readlineSync from 'readline-sync';
import {handlers} from "./src/handlers";
import {CommandContext} from "./src/command-context";
import os from 'os';
import {existsSync, mkdirSync, writeFileSync} from "node:fs";

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
    }

    async run(): Promise<void> {
        do {
            const command = readlineSync.question(`${this.context.username} : `)

            const handler = handlers.find(h => h.accept(command, this.context))

            if (handler) {
                this.context = await handler.handle(command, this.context)
            } else if (command === "exit") {
                break;
            } else if (command === "save") {
                this.save()
            } else {
                console.log("Command not understood, available commands:")
                handlers.forEach(h => console.log(`  - ${h.commandWord} : ${h.description}`))
            }

        } while (true)
    }

    private save(): void {
        let key: string
        if (!this.context?.task?.key) {
            key = readlineSync.question("No task defined yet, enter a title:", {})
        } else {
            key = readlineSync.question(`Suggested name : '${this.context.task.key}', type new name or leave empty to accept suggestion:`, {defaultInput: this.context.task.key})
        }
        if (!key) {
            console.warn("Invalid save name, context not saved.")
            return
        }
        const contextString = JSON.stringify(this.context, null, 2); // The 'null' and '2' arguments format the JSON with indentation for readability
        const jsonFilePath = `${this.codayPath}/${key}`
        writeFileSync(jsonFilePath, contextString, 'utf8');
        console.log("Context saved under: ", jsonFilePath)
    }
}

new MainHandler().run()