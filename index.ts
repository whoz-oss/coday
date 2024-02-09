import * as readlineSync from 'readline-sync';
import {handlers} from "./src/handlers";
import {CommandContext} from "./src/command-context";
import os from 'os';
import {existsSync, mkdirSync, readdirSync} from "node:fs";
import {SaveHandler} from "./src/save-handler";
import {readFileSync} from "fs";

const PROJECT_ROOT: string = '/Users/vincent.audibert/Workspace/biznet.io/app/whoz'
const DATA_PATH: string = "/.coday/"

class MainHandler {

    codayPath: string
    userInfo: os.UserInfo<string>

    constructor() {
        this.userInfo = os.userInfo()
        this.codayPath = this.initCodayPath(this.userInfo)
        handlers.push(new SaveHandler(this.codayPath))
    }

    async run(): Promise<void> {
        let context: CommandContext | null = null

        do {
            if (!context) {
                const files = readdirSync(this.codayPath)
                if (files.length) {
                    console.log("Select a context file:")
                    for (let i = 0; i < files.length; i++) {
                        console.log(`  ${i + 1} - ${files[i]}`)
                    }
                    const selection = readlineSync.question("Type file number: ")
                    if (!!selection) {
                        const index = parseInt(selection)-1
                        const path = `${this.codayPath}/${files[index]}`
                        const data = readFileSync(path, 'utf-8')
                        context = JSON.parse(data)
                    }
                }
                if (!context) {
                    console.log("Defaulting on new context")
                    context = {
                        projectRootPath: PROJECT_ROOT,
                        username: this.userInfo.username
                    }
                }
            }


            const command = readlineSync.question(`${context.username} : `)

            const handler = handlers.find(h => h.accept(command, context!))

            if (handler) {
                context = await handler.handle(command, context)
            } else if (command === "exit") {
                break;
            } else {
                console.log("Command not understood, available commands:")
                handlers.forEach(h => console.log(`  - ${h.commandWord} : ${h.description}`))
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