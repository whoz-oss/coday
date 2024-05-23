import {MainHandler, SaveHandler} from "./src/handler";
import {CommandContext} from "./src/command-context";
import os from 'os';
import {existsSync, mkdirSync} from "node:fs";
import {Interactor} from "./src/interactor";
import {TerminalInteractor} from "./src/terminal-interactor";
import {ProjectHandler} from "./src/handler/project-handler";

const DATA_PATH: string = "/.coday/"
const MAX_ITERATIONS: number = 10

class Coday {

    codayPath: string
    userInfo: os.UserInfo<string>
    context: CommandContext | null = null
    mainHandler: MainHandler
    projectHandler: ProjectHandler

    constructor(private interactor: Interactor) {
        this.userInfo = os.userInfo()
        this.codayPath = this.initCodayPath(this.userInfo)
        this.projectHandler = new ProjectHandler(interactor, this.codayPath, this.userInfo.username)
        this.mainHandler = new MainHandler(
            interactor,
            MAX_ITERATIONS,
            [
                new SaveHandler(interactor, this.codayPath),
                this.projectHandler,
            ],
        )
    }

    async run(): Promise<void> {
        // Main loop to keep waiting for user input
        do {
            // initiate context in loop for when context is cleared
            if (!this.context) {
                this.context = this.projectHandler.initContext()
                continue
            }
            // allow user input
            this.interactor.addSeparator()
            const userCommand = this.interactor.promptText(`${this.userInfo.username}`)
            this.interactor.addSeparator()

            // quit loop if user wants to exit
            if (userCommand === this.mainHandler.exitWord) {
                break;
            }
            // reset context and project selection
            if (userCommand === this.mainHandler.resetWord) {
                this.context = null
                this.projectHandler.resetProjectSelection()
                continue
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
                this.interactor.displayText(`Coday config folder created at: ${codayPath}`);
            } else {
                this.interactor.displayText(`Coday config folder used: ${codayPath}`);
            }
        } catch (error) {
            this.interactor.error(`Error creating directory: ${error}`);
        }
        return codayPath
    }
}

new Coday(new TerminalInteractor()).run()