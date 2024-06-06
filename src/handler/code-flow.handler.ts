import { CommandHandler } from "./command-handler"
import {CommandContext} from "../command-context";

export class CodeFlowHandler extends CommandHandler {
    commandWord: string = 'code'
    description: string = 'expand the request into a flow of request : analysis, implementation, test and review'

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const cmd = this.getSubCommand(command)
        
        const expertInstructions = `
        You are given this assignment: ${cmd}. 
        To complete it:
        1. Analyse the request thoroughly, identifying all impacted files and any dependencies.
        2. Break down the assignment into smaller sequential steps using the 'subTask' provided function.
        3. Consider the project's architecture and code conventions as described in the project description for technical sub-tasks.
        4. For each sub-task, think about how an expert software developer would approach the problem:
            - Initial setup and preparation.
            - Implementation details.
            - Testing strategies, including writing new tests if necessary or requested or allowed.
            - Validation and debugging using project scripts if appropriate.
            - Commit messages and documentation.

        Make sure each sub-task is as atomic as possible and logically ordered to ensure efficient workflow.
        `

        const newCommand = `ai ${expertInstructions}`
        context.addCommands(newCommand)
        return context
    }
}
