import { CommandContext } from "../command-context";
import {CommandHandler} from "./command-handler";

export class CodeFlowHandler extends CommandHandler {
    commandWord: string = 'code'
    description: string = 'expand the request into a flow of request : analysis, implementation, test and review'
    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const cmd  = this.getSubCommand(command)
        const commands = [
            `ai analyse this request by looking for all impacted and dependent files. Look also around targeted files to get some context or patterns. Deduce also impacted files from the project description and the entities concerned. From the analysis, establish a plan of action but do not edit any file for now. Request: ${cmd}`,
            `ai implement the plan you established by editing the impacted files. Respect while doing so the code conventions in the project description. When writing code, do not add comment from your part on what you did, but comment only the non-trivial code. Give me a short explanation of what you did and any specific point of concern.`,
            // `ai look for test files to update regarding the impacted code. If no existing test files cover the edited ones, and surrounding code files have their test counterparts, then write test files based on the surrounding test and try to follow conventions you can find in them.`,
            `ai review the changes done on the repository (can use git commands available) and apply corrections by editing files if necessary. If nothing to correct given the previously done analysis, do nothing.`
        ]
        // edit in place the command queue to add the flow
        context.commandQueue.unshift(...commands)
        return context
    }

}