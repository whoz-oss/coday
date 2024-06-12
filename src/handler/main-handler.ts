import {CommandHandler} from "./command-handler";
import {NestedHandler} from "./nested-handler";
import {OpenaiHandler} from "./openai-handler";
import {Interactor} from "../interactor";
import {GitHandler} from "./git-handler";
import {DebugHandler} from "./debug-handler";
import {RunBashHandler} from "./run-bash-handler";
import {CodeFlowHandler} from "./code-flow.handler";
import {CommandContext} from "../command-context";

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
            new RunBashHandler(interactor),
            new DebugHandler(interactor),
            new CodeFlowHandler(),
            this.openaiHandler
        ]
    }

    accept(command: string, context: CommandContext): boolean {
        return true
    }

    async handle(_: string, context: CommandContext): Promise<CommandContext> {
        let count = 0
        let command: string | undefined
        do {
            command = context.getFirstCommand()
            count++
            if (this.isHelpAsked(command)) {
                this.interactor.displayText("  - [any other text] : defaults to asking the AI with the current context.")
                this.interactor.displayText(`  - ${this.resetWord} : resets Coday's context`)
                this.interactor.displayText(`  - ${this.exitWord} : quits the program`)
                command = undefined
                continue
            }
            if (!command) {
                continue
            }

            // find first handler
            const handler: CommandHandler | undefined = this.handlers.find((h: CommandHandler) => h.accept(command!, context))

            try {
                if (handler) {
                    // TODO: remove very bad pattern of re-assigning context
                    context = await handler.handle(command!, context)
                } else {
                    // default case: repackage the command as an open question for AI
                    context.addCommands(`${this.openaiHandler.commandWord} ${command}`)
                }
            } catch (error) {
                this.interactor.error(`An error occurred while trying to process your request: ${error}`)
            }
        } while (!!command && count < this.maxIterations)
        if (count >= this.maxIterations) {
            this.interactor.warn('Maximum iterations reached for a command')
        }
        return context
    }

}
