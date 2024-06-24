import os from "os";
import path from "path";
import {CommandContext} from "./command-context";
import {Interactor} from "./interactor";
import {OpenaiHandler} from "./handler";
import {GitHandler} from "./handler/git-handler";
import {RunBashHandler} from "./handler/run-bash-handler";
import {DebugHandler} from "./handler/debug-handler";
import {CodeFlowHandler} from "./handler/code-flow.handler";
import {ConfigHandler} from "./handler/config-handler";
import {existsSync, mkdirSync} from "node:fs";
import {CommandHandler} from "./handler/command-handler";

const DATA_PATH = "/.coday"
const MAX_ITERATIONS = 10

interface CodayOptions {
    interactive: boolean
    project?: string
    prompts?: string[]
    maxIterations?: number
}

export class Coday {

    codayPath: string
    userInfo: os.UserInfo<string>
    context: CommandContext | null = null
    projectHandler: ConfigHandler

    // Properties from MainHandler
    commandWord: string = ''
    description: string = ''
    exitWord = 'exit'
    resetWord = 'reset'
    handlers: CommandHandler[]
    openaiHandler: OpenaiHandler
    maxIterations: number

    constructor(private interactor: Interactor, private options: CodayOptions) {
        this.userInfo = os.userInfo()
        this.codayPath = this.initCodayPath(this.userInfo)
        this.projectHandler = new ConfigHandler(interactor, this.userInfo.username)
        
        // Initialize MainHandler properties
        this.openaiHandler = new OpenaiHandler(interactor)
        this.maxIterations = options.maxIterations || MAX_ITERATIONS
        this.handlers = [
            this.projectHandler,
            new GitHandler(interactor),
            new RunBashHandler(interactor),
            new DebugHandler(interactor),
            new CodeFlowHandler(),
            this.openaiHandler
        ]
    }

    accept(command: string, context: CommandContext): boolean {
        return true
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        let count = 0
        let currentCommand: string | undefined = command
        do {
            currentCommand = context.getFirstCommand()
            count++
            if (this.isHelpAsked(currentCommand)) {
                this.interactor.displayText("Available commands:")
                this.interactor.displayText("  - help/h/[nothing] : displays this help message")
                this.handlers
                    .slice()
                    .sort((a, b) => a.commandWord.localeCompare(b.commandWord))
                    .forEach(h => this.interactor.displayText(`  - ${h.commandWord} : ${h.description}`))
                this.interactor.displayText("  - [any other text] : defaults to asking the AI with the current context.")
                this.interactor.displayText(`  - ${this.resetWord} : resets Coday's context`)
                this.interactor.displayText(`  - ${this.exitWord} : quits the program`)
                currentCommand = undefined
                continue
            }
            if (!currentCommand) {
                continue
            }

            // find first handler
            const handler: CommandHandler | undefined = this.handlers.find(h => h.accept(currentCommand!, context))

            try {
                if (handler) {
                    context = await handler.handle(currentCommand, context)
                } else {
                    // default case: repackage the command as an open question for AI
                    context.addCommands(`${this.openaiHandler.commandWord} ${currentCommand}`)
                }
            } catch (error) {
                this.interactor.error(`An error occurred while trying to process your request: ${error}`)
            }
        } while (!!currentCommand && count < this.maxIterations)
        if (count >= this.maxIterations) {
            this.interactor.warn('Maximum iterations reached for a command')
        }
        return context
    }

    private isHelpAsked(command: string | undefined): boolean {
        return command === "" || command === "help" || command === "h"
    }

    async run(): Promise<void> {
        // Main loop to keep waiting for user input
        do {
            // initiate context in loop for when context is cleared
            if (!this.context) {
                this.context = await this.projectHandler.initContext(this.options.project)
                continue
            }
            // allow user input
            this.interactor.addSeparator()
            const userCommand = this.interactor.promptText(`${this.userInfo.username}`)
            this.interactor.addSeparator()

            // quit loop if user wants to exit
            if (userCommand === this.exitWord) {
                break
            }
            // reset context and project selection
            if (userCommand === this.resetWord) {
                this.context = null
                this.projectHandler.resetProjectSelection()
                continue
            }

            // add the user command to the queue and let handlers decompose it in many and resolve them ultimately
            this.context.addCommands(userCommand)

            this.context = await this.handle(userCommand, this.context)

        } while (this.options.interactive)
    }

    private initCodayPath(userInfo: os.UserInfo<string>): string {
        const codayPath = path.join(userInfo.homedir, DATA_PATH)

        try {
            if (!existsSync(codayPath)) {
                mkdirSync(codayPath, {recursive: true})
                this.interactor.displayText(`Coday config folder created at: ${codayPath}`)
            } else {
                this.interactor.displayText(`Coday config folder used: ${codayPath}`)
            }
        } catch (error) {
            this.interactor.error(`Error creating directory: ${error}`)
        }
        return codayPath
    }
}
