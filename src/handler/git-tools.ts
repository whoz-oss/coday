import {CommandContext} from "../command-context"
import {runBash} from "../function/run-bash"
import {AssistantToolFactory, Tool} from "./init-tools"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {Interactor} from "../interactor"
import {Beta} from "openai/resources"
import AssistantTool = Beta.AssistantTool
import {configService} from "../service/config-service";
import {ApiName} from "../service/coday-config";

export class GitTools extends AssistantToolFactory {

    constructor(interactor: Interactor) {
        super(interactor)
    }

    protected hasChanged(context: CommandContext): boolean {
        return this.lastToolInitContext?.project.root !== context.project.root
    }

    protected buildTools(context: CommandContext): Tool[] {
        const result: Tool[] = []

        if (!configService.hasIntegration(ApiName.GIT)) {
            return result
        }

        const gitStatus = async () => {
            return await runBash({
                command: 'git status',
                root: context.project.root,
                interactor: this.interactor
            });
        }

        const gitStatusFunction: AssistantTool & RunnableToolFunction<{}> = {
            type: "function",
            function: {
                name: "gitStatusFunction",
                description: "run git status command, providing a status of all modified files since last commit.",
                parameters: {
                    type: "object",
                    properties: {}
                },
                parse: JSON.parse,
                function: gitStatus
            }
        }
        result.push(gitStatusFunction)

        const gitDiff = async () => {
            return await runBash({
                command: 'git diff',
                root: context.project.root,
                interactor: this.interactor
            });
        }

        const gitDiffFunction: AssistantTool & RunnableToolFunction<{}> = {
            type: "function",
            function: {
                name: "gitDiffFunction",
                description: "run git diff command, an exhaustive list of all changes in progress.",
                parameters: {
                    type: "object",
                    properties: {}
                },
                parse: JSON.parse,
                function: gitDiff
            }
        }
        result.push(gitDiffFunction)

        return result
    }
}
