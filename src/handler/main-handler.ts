import {CommandHandler} from "./command-handler";
import {JiraHandler} from "./jira-handler";
import {NestedHandler} from "./nested-handler";
import {CommandContext} from "../command-context";
import {OpenaiHandler} from "./openai-handler";
import {Interactor} from "../interactor";
import {GitHandler} from "./git-handler";
import {DebugHandler} from "./debug-handler";
import {RunBashHandler} from "./run-bash-handler";
import {CodeFlowHandler} from "./code-flow.handler";

export class MainHandler extends NestedHandler {
    commandWord: string = ''
    description: string = ''
    exitWord = 'exit'
    resetWord = 'reset'
    handlers: CommandHandler[]
    openaiHandler: OpenaiHandler

    constructor(
        interactor: Interactor,
        private maxIterations: number = 10,
        defaultHandlers: CommandHandler[] = [],
    ) {
        super(interactor)
        this.openaiHandler = new OpenaiHandler(interactor)
        this.handlers = [
            ...defaultHandlers,
            new GitHandler(interactor),
            new JiraHandler(interactor),
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
        let innerContext = context
        while (innerContext.commandQueue.length > 0 && count < this.maxIterations) {
            count++
            const command: string | undefined = innerContext.commandQueue.shift()
            if (this.isHelpAsked(command)) {
                this.interactor.displayText("  - [any other text] : defaults to asking the AI with the current context.")
                this.interactor.displayText(`  - ${this.resetWord} : resets Coday's context`)
                this.interactor.displayText(`  - ${this.exitWord} : quits the program`)
                continue
            }

            // find first handler
            const handler: CommandHandler | undefined = this.handlers.find((h: CommandHandler) => h.accept(command!, innerContext!))

            try {
                // try handlers in their preference order
                if (handler) {
                    innerContext = await handler.handle(command!, innerContext)
                } else {
                    // default case: repackage the command as an open question for AI
                    innerContext.commandQueue.unshift(`${this.openaiHandler.commandWord} ${command}`)
                }
            } catch (error) {
                this.interactor.error(`An error occurred while trying to process your request: ${error}`)
            }
        }
        if (count > this.maxIterations) {
            this.interactor.warn('Maximum iterations reached for a command')
        }
        return innerContext
    }

}
