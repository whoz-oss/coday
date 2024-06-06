import {CommandContext} from "../command-context"
import {runBash} from "../function/run-bash"
import {AssistantToolFactory, Tool} from "./init-tools"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {Interactor} from "../interactor"
import {Beta} from "openai/resources"
import {gitAdd} from "../function/git-add"
import {gitCommit} from "../function/git-commit"
import {gitListBranches} from "../function/git-list-branches"
import {gitCreateBranch} from "../function/git-create-branch"
import {configService} from "../service/config-service"
import {ApiName} from "../service/coday-config"
import AssistantTool = Beta.AssistantTool;

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
            })
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

        const gitDiff = async () => {
            return await runBash({
                command: 'git diff',
                root: context.project.root,
                interactor: this.interactor
            })
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

        const addFilesToStaging = async ({filePaths}: { filePaths: string[] }) => {
            return await gitAdd({
                filePaths,
                root: context.project.root,
                interactor: this.interactor
            })
        }

        const gitAddFunction: AssistantTool & RunnableToolFunction<{ filePaths: string[] }> = {
            type: "function",
            function: {
                name: "gitAddFunction",
                description: "Add files to staging area. Please add only relevant files and check with git status before.",
                parameters: {
                    type: "object",
                    properties: {
                        filePaths: {
                            type: "array",
                            items: {
                                type: "string"
                            },
                            description: "List of file paths to add to staging area"
                        }
                    },
                },
                parse: JSON.parse,
                function: addFilesToStaging
            }
        }

        const commitChanges = async ({message}: { message: string }) => {
            return await gitCommit({
                message,
                root: context.project.root,
                interactor: this.interactor
            })
        }

        const gitCommitFunction: AssistantTool & RunnableToolFunction<{ message: string }> = {
            type: "function",
            function: {
                name: "gitCommitFunction",
                description: "Commit changes with a message. Write a short commit message starting with the id of the current task or ticket or subject. Add a short paragraph after if significant changes (ie not for typos or comments commits).",
                parameters: {
                    type: "object",
                    properties: {
                        message: {
                            type: "string",
                            description: "Commit message"
                        }
                    },
                },
                parse: JSON.parse,
                function: commitChanges
            }
        }

        const listGitBranches = async () => {
            return await gitListBranches({
                root: context.project.root,
                interactor: this.interactor
            })
        }

        const gitListBranchesFunction: AssistantTool & RunnableToolFunction<{}> = {
            type: "function",
            function: {
                name: "gitListBranchesFunction",
                description: "List all git branches, local and remote.",
                parameters: {
                    type: "object",
                    properties: {}
                },
                parse: JSON.parse,
                function: listGitBranches
            }
        }

        const createBranch = async ({branchName, baseBranch}: { branchName: string, baseBranch?: string }) => {
            return await gitCreateBranch({
                branchName,
                baseBranch,
                root: context.project.root,
                interactor: this.interactor
            })
        }

        const gitCreateBranchFunction: AssistantTool & RunnableToolFunction<{
            branchName: string,
            baseBranch?: string
        }> = {
            type: "function",
            function: {
                name: "gitCreateBranchFunction",
                description: "Create a new branch from the head of another branch or the current branch.",
                parameters: {
                    type: "object",
                    properties: {
                        branchName: {
                            type: "string",
                            description: "Name of the new branch"
                        },
                        baseBranch: {
                            type: "string",
                            description: "(Optional) Name of the base branch; defaults to the current branch"
                        }
                    },
                },
                parse: JSON.parse,
                function: createBranch
            }
        }

        result.push(...[
                gitListBranchesFunction,
                gitCommitFunction,
                gitDiffFunction,
                gitAddFunction,
                gitStatusFunction,
                gitCreateBranchFunction,
            ]
        )

        return result
    }
}
