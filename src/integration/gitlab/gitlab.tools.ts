import {AssistantToolFactory, Tool} from "../assistant-tool-factory"
import {CommandContext} from "../../model/command-context"
import {Interactor} from "../../model/interactor"
import {getMergeRequest} from "./get-merge-request"
import {addGlobalComment} from "./add-global-comment"
import {addMRThread} from "./add-mr-thread"
import {Beta} from "openai/resources"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {getIssue} from "./get-issue"
import {IntegrationName} from "../../model/integration-name"
import {integrationService} from "../../service/integration.service"
import AssistantTool = Beta.AssistantTool

export class GitLabTools extends AssistantToolFactory {
  constructor(interactor: Interactor) {
    super(interactor)
  }
  
  protected hasChanged(context: CommandContext): boolean {
    return this.lastToolInitContext?.project.root !== context.project.root
  }
  
  protected buildTools(context: CommandContext): Tool[] {
    const result: Tool[] = []
    if (!integrationService.hasIntegration(IntegrationName.GITLAB)) {
      return result
    }
    
    const gitlabBaseUrl = integrationService.getApiUrl(IntegrationName.GITLAB)
    const gitlabUsername = integrationService.getUsername(IntegrationName.GITLAB)
    const gitlabApiToken = integrationService.getApiKey(IntegrationName.GITLAB)
    if (!(gitlabBaseUrl && gitlabUsername && gitlabApiToken)) {
      return result
    }
    
    const retrieveMR = ({mergeRequestId}: { mergeRequestId: string }) => {
      return getMergeRequest(mergeRequestId, gitlabBaseUrl, gitlabApiToken, gitlabUsername, this.interactor)
    }
    
    const retrieveGitlabMRFunction: AssistantTool & RunnableToolFunction<{
      mergeRequestId: string
    }> = {
      type: "function",
      function: {
        name: "retrieveGitlabMR",
        description: "Retrieve GitLab merge request details by merge request ID.",
        parameters: {
          type: "object",
          properties: {
            mergeRequestId: {type: "string", description: "GitLab merge request ID"}
          }
        },
        parse: JSON.parse,
        function: retrieveMR
      }
    }
    
    const retrieveIssue = ({issueId}: { issueId: string }) => {
      return getIssue(issueId, gitlabBaseUrl, gitlabApiToken, gitlabUsername, this.interactor)
    }
    
    const retrieveGitlabIssueFunction: AssistantTool & RunnableToolFunction<{
      issueId: string
    }> = {
      type: "function",
      function: {
        name: "retrieveGitlabIssue",
        description: "Retrieve GitLab issue details by issue ID, usually a number.",
        parameters: {
          type: "object",
          properties: {
            issueId: {type: "string", description: "GitLab issue ID, usually a number."}
          }
        },
        parse: JSON.parse,
        function: retrieveIssue
      }
    }
    
    const addGlobalCommentFunction = ({mergeRequestId, comment}: {
      mergeRequestId: string
      comment: string
    }) => {
      return addGlobalComment({mergeRequestId, comment, gitlabBaseUrl, gitlabApiToken, interactor: this.interactor})
    }
    
    const addGlobalCommentTool: AssistantTool & RunnableToolFunction<{
      projectId: string
      mergeRequestId: string
      comment: string
    }> = {
      type: "function",
      function: {
        name: "addGlobalComment",
        description: "Add a global comment to a GitLab merge request. Use only when reviewing a merge request.",
        parameters: {
          type: "object",
          properties: {
            mergeRequestId: {type: "string", description: "GitLab merge request ID"},
            comment: {type: "string", description: "Comment to add"}
          }
        },
        parse: JSON.parse,
        function: addGlobalCommentFunction
      }
    }
    
    const addMRThreadFunction = ({
                                   mergeRequestId,
                                   base_sha,
                                   head_sha,
                                   start_sha,
                                   oldFilePath,
                                   newFilePath,
                                   comment,
                                   oldLineNumber,
                                   newLineNumber
                                 }: {
      mergeRequestId: string
      base_sha: string, head_sha: string, start_sha: string
      oldFilePath: string
      newFilePath: string
      comment: string
      oldLineNumber: number | null
      newLineNumber: number | null
    }) => {
      return addMRThread({
        mergeRequestId,
        base_sha,
        head_sha,
        start_sha,
        oldFilePath,
        newFilePath,
        comment,
        oldLineNumber,
        newLineNumber,
        gitlabBaseUrl,
        gitlabApiToken,
        interactor: this.interactor
      })
    }
    
    const addMRThreadTool: AssistantTool & RunnableToolFunction<{
      mergeRequestId: string
      base_sha: string, head_sha: string, start_sha: string,
      oldFilePath: string
      newFilePath: string
      comment: string
      oldLineNumber: number | null
      newLineNumber: number | null
    }> = {
      type: "function",
      function: {
        name: "addMRThread",
        description: "Use only when reviewing a merge request. Add feedback to a specific change of file of a MR located at a specific line. The file is identified by its old file path and its new file path. The line of code is identified by its old line number and its new line number. For a new line, the old line number should be null and for removed line the new line number should null.",
        parameters: {
          type: "object",
          properties: {
            mergeRequestId: {type: "string", description: "GitLab merge request ID"},
            base_sha: {type: "string", description: "Base SHA of the merge request"},
            head_sha: {type: "string", description: "Head SHA of the merge request"},
            start_sha: {type: "string", description: "Start SHA of the merge request"},
            oldFilePath: {type: "string", description: "Path of the file before changes"},
            newFilePath: {type: "string", description: "Path of the file after changes"},
            comment: {type: "string", description: "Comment to add to the merge request"},
            oldLineNumber: {
              type: ["number", "null"],
              description: "Line number in the old file (use null if the line is new)"
            },
            newLineNumber: {
              type: ["number", "null"],
              description: "Line number in the new file (use null if the line is removed)"
            }
          }
        },
        parse: JSON.parse,
        function: addMRThreadFunction
      }
    }
    
    result.push(retrieveGitlabMRFunction)
    result.push(retrieveGitlabIssueFunction)
    result.push(addGlobalCommentTool)
    result.push(addMRThreadTool)
    
    return result
  }
}
