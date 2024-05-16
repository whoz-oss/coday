import {GitBranchHandler} from "./git-branch-handler";
import {CommandHandler} from "./command-handler";
import {JiraHandler} from "./jira-handler";
import {NestedHandler} from "./nested-handler";
import {CommandContext} from "./command-context";
import {OpenaiHandler} from "./openai-handler";


export class MainHandler implements NestedHandler {
    commandWord: string = ''
    description: string = ''
    exitWord = 'exit'
    handlers: CommandHandler[]
    openaiHandler: OpenaiHandler

    constructor(
        private maxIterations: number = 10,
        defaultHandlers: CommandHandler[] = []
    ) {
        this.openaiHandler = new OpenaiHandler()
        this.handlers = [
            ...defaultHandlers,
            new GitBranchHandler(),
            new JiraHandler(),
            this.openaiHandler
        ]
    }

    accept(command: string, context: CommandContext): boolean {
        return true;
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        let count = 0
        let innerContext = context
        while (innerContext.commandQueue.length > 0 && count < this.maxIterations) {
            count++
            const command: string | undefined = innerContext.commandQueue.shift()
            if (!command || command ===  'help' || command === 'h') {
                console.log("Available commands:")
                console.log(`  - ${this.exitWord} : quits the program`)
                console.log("  - help : displays this help message")
                this.handlers.forEach(h => console.log(`  - ${h.commandWord} : ${h.description}`))
                console.log("  - [any other text] : defaults to asking the AI with the current context.")
                continue;
            }

            // find first handler
            const handler: CommandHandler | undefined = this.handlers.find((h: CommandHandler) => h.accept(command, innerContext!))

            try {
                // try handlers in their preference order
                if (handler) {
                    innerContext = await handler.handle(command, innerContext)
                } else {
                    // default case: repackage the command as an open question for AI
                    innerContext.commandQueue.unshift(`${this.openaiHandler.commandWord} ${command}`)
                }
            } catch (error) {
                console.error(`An error occurred while trying to process your request: ${error}`)
            }
        }
        if (count > this.maxIterations) {
            console.warn('Maximum iterations reached for a command')
        }
        return innerContext
    }

}