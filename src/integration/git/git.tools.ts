import {runBash} from "../../function/run-bash"
import {gitAdd} from "./git-add"
import {gitCommit} from "./git-commit"
import {gitListBranches} from "./git-list-branches"
import {gitCreateBranch} from "./git-create-branch"
import {gitLog} from "./git-log"
import {gitShow} from "./git-show"
import {integrationService} from "../../service/integration.service"
import {gitCheckoutBranch} from "./git-checkout-branch"
import {CommandContext, IntegrationName, Interactor} from "../../model"
import {AssistantToolFactory, Tool} from "../assistant-tool-factory"
import {FunctionTool} from "../types"

export class GitTools extends AssistantToolFactory {
  constructor(interactor: Interactor) {
    super(interactor)
  }
  
  protected hasChanged(context: CommandContext): boolean {
    return this.lastToolInitContext?.project.root !== context.project.root
  }
  
  protected buildTools(context: CommandContext): Tool[] {
    const result: Tool[] = []
    
    if (!integrationService.hasIntegration(IntegrationName.GIT)) {
      return result
    }
    
    const gitStatus = async () => {
      return await runBash({
        command: "git status",
        root: context.project.root,
        interactor: this.interactor
      })
    }
    
    const gitStatusTool: FunctionTool<{}> = {
      type: "function",
      function: {
        name: "gitStatusTool",
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
        command: "git diff",
        root: context.project.root,
        interactor: this.interactor
      })
    }
    
    const gitDiffTool: FunctionTool<{}> = {
      type: "function",
      function: {
        name: "gitDiffTool",
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
    
    const gitAddTool: FunctionTool<{ filePaths: string[] }> = {
      type: "function",
      function: {
        name: "gitAddTool",
        description: "DO NOT USE UNLESS EXPLICITLY ASKED TO.Add files to staging area. Please add only relevant files and check with git status before.",
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
    
    const gitCommitTool: FunctionTool<{ message: string }> = {
      type: "function",
      function: {
        name: "gitCommitTool",
        description: "DO NOT USE UNLESS EXPLICITLY ASKED TO. Commit changes with a message. Write a short commit message starting with the id of the current task or ticket or subject. Add a short paragraph after if significant changes (ie not for typos or comments commits).",
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
    
    const gitListBranchesTool: FunctionTool<{}> = {
      type: "function",
      function: {
        name: "gitListBranchesTool",
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
    
    const gitCreateBranchTool: FunctionTool<{ branchName: string, baseBranch?: string }> = {
      type: "function",
      function: {
        name: "gitCreateBranchTool",
        description: "DO NOT USE UNLESS EXPLICITLY ASKED TO. Create a new branch from the head of another branch or the current branch.",
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
    
    const gitLogFunction = async ({params}: { params: string }) => {
      return await gitLog({
        params,
        root: context.project.root,
        interactor: this.interactor
      })
    }
    
    const gitLogTool: FunctionTool<{ params: string }> = {
      type: "function",
      function: {
        name: "gitLogTool",
        description: "run git log command to list commits made in the repository. The params parameter allows you to pass additional arguments to the git log command.",
        parameters: {
          type: "object",
          properties: {
            params: {
              type: "string",
              description: "Additional parameters for the git log command"
            }
          },
        },
        parse: JSON.parse,
        function: gitLogFunction
      }
    }
    
    const gitShowFunction = async ({params}: { params: string }) => {
      return await gitShow({
        params,
        root: context.project.root,
        interactor: this.interactor
      })
    }
    
    const gitShowTool: FunctionTool<{ params: string }> = {
      type: "function",
      function: {
        name: "gitShowTool",
        description: "run git show command with the specified parameters.",
        parameters: {
          type: "object",
          properties: {
            params: {
              type: "string",
              description: "Parameters for the git show command"
            }
          },
        },
        parse: JSON.parse,
        function: gitShowFunction
      }
    }
    
    const checkoutBranch = async ({branchName}: { branchName: string }) => {
      return await gitCheckoutBranch({
        branchName,
        root: context.project.root,
        interactor: this.interactor
      })
    }
    
    const gitCheckoutBranchTool: FunctionTool<{ branchName: string }> = {
      type: "function",
      function: {
        name: "gitCheckoutBranchTool",
        description: "Checkout an existing branch.",
        parameters: {
          type: "object",
          properties: {
            branchName: {
              type: "string",
              description: "Name of the branch to checkout"
            }
          },
        },
        parse: JSON.parse,
        function: checkoutBranch
      }
    }
    
    result.push(
      gitListBranchesTool,
      gitCommitTool,
      gitDiffTool,
      gitAddTool,
      gitStatusTool,
      gitCreateBranchTool,
      gitLogTool,
      gitShowTool,
      gitCheckoutBranchTool
    )
    
    return result
  }
}
