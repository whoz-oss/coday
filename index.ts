import * as readlineSync from 'readline-sync';
import { MainHandler} from "./src/main-handler";
import {CommandContext} from "./src/command-context";
import os from 'os';
import {existsSync, mkdirSync} from "node:fs";
import {LoadHandler} from "./src/load-handler";
import {SaveHandler} from "./src/save-handler";
import {Interactor} from "./src/interactor";
import {TerminalInteractor} from "./src/terminal-interactor";
import {readFileSync} from "fs";
import {RunnableToolFunction} from "openai/lib/RunnableFunction";
import {Beta} from "openai/resources";
import AssistantTool = Beta.AssistantTool;

const PROJECT_ROOT: string = '/Users/vincent.audibert/Workspace/coday'
const DATA_PATH: string = "/.coday/"
const MAX_ITERATIONS: number = 10

class Coday {

    codayPath: string
    userInfo: os.UserInfo<string>
    loadHandler: LoadHandler
    context: CommandContext | null = null
    mainHandler: MainHandler


    readFileByPath = ({path}: {path: string}) => {
        const fullPath = `${PROJECT_ROOT}/${path}`
        try {
            this.interactor.displayText(`reading file ${path}`)
            return readFileSync(fullPath).toString()
        } catch (err) {
            this.interactor.error(`Error reading file ${path}`)
            console.error(err)
            return "Error reading file"
        }
    }

    readFileByPathFunction: AssistantTool & RunnableToolFunction<{path: string}> = {
        type: "function",
        function: {
            name: "readFileByPath",
            description: "read the content of the file at the given path in the project",
            parameters: {
                type: "object",
                properties: {
                    path: { type: "string" }
                }
            },
            parse: JSON.parse,
            function: this.readFileByPath
        }
    }

    constructor(private interactor: Interactor) {
        this.userInfo = os.userInfo()
        this.codayPath = this.initCodayPath(this.userInfo)
        this.loadHandler = new LoadHandler(interactor, this.codayPath, PROJECT_ROOT, this.userInfo.username)
        this.mainHandler = new MainHandler(
            interactor,
            MAX_ITERATIONS,
            [
                new SaveHandler(interactor, this.codayPath),
                this.loadHandler
            ],
            [this.readFileByPathFunction]
        )
    }

    async run(): Promise<void> {
        // Main loop to keep waiting for user input
        do {
            // initiate context in loop for when context is cleared
            if (!this.context) {
                this.context = await this.loadHandler.handle("load", this.loadHandler.defaultContext)
            }
            // allow user input
            this.interactor.addSeparator()
            const userCommand = readlineSync.question(`${this.context.username} : `)

            // quit loop if user wants to exit
            if (userCommand === this.mainHandler.exitWord) {
                break;
            }

            // add the user command to the queue and let handlers decompose it in many and resolve them ultimately
            this.context.commandQueue.push(userCommand)

            this.context = await this.mainHandler.handle(userCommand, this.context)

        } while (true)
    }

    private initCodayPath(userInfo: os.UserInfo<string>): string {
        // define data path to store settings and other
        const codayPath = `${userInfo.homedir}${DATA_PATH}`

        try {
            if (!existsSync(codayPath)) {
                mkdirSync(codayPath, {recursive: true});
                this.interactor.displayText(`Coday data folder created at: ${codayPath}`);
            } else {
                this.interactor.displayText(`Coday data folder used: ${codayPath}`);
            }
        } catch (error) {
            this.interactor.error(`Error creating directory: ${error}`);
        }
        return codayPath
    }
}

new Coday(new TerminalInteractor()).run()