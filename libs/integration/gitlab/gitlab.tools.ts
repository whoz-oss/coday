import { getMergeRequest } from './get-merge-request'
import { addGlobalComment } from './add-global-comment'
import { addMRThread } from './add-mr-thread'
import { getIssue } from './get-issue'
import { IntegrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { listIssues } from './list-issues'
import { listMergeRequests } from './list-merge-requests'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'

export class GitLabTools extends AssistantToolFactory {
  name = 'GITLAB'

  constructor(
    interactor: Interactor,
    private integrationService: IntegrationService
  ) {
    super(interactor)
  }

  protected hasChanged(context: CommandContext): boolean {
    return this.lastToolInitContext?.project.root !== context.project.root
  }

  protected buildTools(): CodayTool[] {
    const result: CodayTool[] = []
    const gitlab = this.integrationService.getIntegration('GITLAB')
    if (!gitlab) {
      return result
    }
    const { apiUrl, apiKey } = gitlab

    if (!apiKey) {
      this.interactor.warn('Gitlab access token not set')
    }
    if (!apiUrl) {
      this.interactor.warn('Gitlab api url not set')
    }
    if (!apiKey || !apiUrl) {
      return result
    }

    const retrieveMR = ({ mergeRequestId }: { mergeRequestId: string }) => {
      return getMergeRequest(mergeRequestId, apiUrl, apiKey, this.interactor)
    }

    const retrieveGitlabMRFunction: FunctionTool<{
      mergeRequestId: string
    }> = {
      type: 'function',
      function: {
        name: 'retrieveGitlabMR',
        description: 'Retrieve GitLab merge request details by merge request ID.',
        parameters: {
          type: 'object',
          properties: {
            mergeRequestId: { type: 'string', description: 'GitLab merge request ID' },
          },
        },
        parse: JSON.parse,
        function: retrieveMR,
      },
    }

    const retrieveIssue = ({ issueId }: { issueId: string }) => {
      return getIssue(issueId, apiUrl, apiKey, this.interactor)
    }

    const retrieveGitlabIssueFunction: FunctionTool<{
      issueId: string
    }> = {
      type: 'function',
      function: {
        name: 'retrieveGitlabIssue',
        description: 'Retrieve GitLab issue details by issue ID, usually a number.',
        parameters: {
          type: 'object',
          properties: {
            issueId: { type: 'string', description: 'GitLab issue ID, usually a number.' },
          },
        },
        parse: JSON.parse,
        function: retrieveIssue,
      },
    }

    const retrieveIssues = ({ criteria }: { criteria: string }) => {
      return listIssues({ criteria, integration: gitlab, interactor: this.interactor })
    }

    const retrieveGitlabIssuesFunction: FunctionTool<{ criteria: string }> = {
      type: 'function',
      function: {
        name: 'retrieveGitlabIssues',
        description: `Retrieve GitLab issues by criteria.`,
        parameters: {
          type: 'object',
          properties: {
            criteria: {
              type: 'string',
              description: `string representing the parameters that will be passed as per http params. Different conditions can be combined with "&". Examples:
            labels=foo
            labels=foo,bar
            labels=foo,bar&state=opened
            milestone=1.0.0
            milestone=1.0.0&state=
            search=issue+title+or+description
            state=closed
            state=opened
            page=2`,
            },
          },
        },
        parse: JSON.parse,
        function: retrieveIssues,
      },
    }

    const retrieveMergeRequests = ({ criteria }: { criteria: string }) => {
      return listMergeRequests({ criteria, integration: gitlab, interactor: this.interactor })
    }

    const retrieveGitlabMergeRequestsFunction: FunctionTool<{ criteria: string }> = {
      type: 'function',
      function: {
        name: 'retrieveGitlabMergeRequests',
        description: `Retrieve GitLab merge requests by criteria.`,
        parameters: {
          type: 'object',
          properties: {
            criteria: {
              type: 'string',
              description: `string representing the parameters that will be passed as per http params. Different conditions can be combined with "&". Examples:
            labels=foo
            labels=foo,bar
            labels=foo,bar&state=opened
            milestone=release
            labels=team_a,category_b
            labels=none
            target_branch=master
            source_branch=hotfix
            state=all
            state=opened,closed,locked,merged
            search=title+or+description
            state=closed
            approver_ids=Any
            approver_ids=None
            approved=yes
            approved=no
            page=2`,
            },
          },
        },
        parse: JSON.parse,
        function: retrieveMergeRequests,
      },
    }

    const addGlobalCommentFunction = ({ mergeRequestId, comment }: { mergeRequestId: string; comment: string }) => {
      return addGlobalComment({
        mergeRequestId,
        comment,
        gitlabBaseUrl: apiUrl,
        gitlabApiToken: apiKey,
        interactor: this.interactor,
      })
    }

    const addGlobalCommentTool: FunctionTool<{
      projectId: string
      mergeRequestId: string
      comment: string
    }> = {
      type: 'function',
      function: {
        name: 'addGlobalComment',
        description: 'Add a global comment to a GitLab merge request. Use only when reviewing a merge request.',
        parameters: {
          type: 'object',
          properties: {
            mergeRequestId: { type: 'string', description: 'GitLab merge request ID' },
            comment: { type: 'string', description: 'Comment to add' },
          },
        },
        parse: JSON.parse,
        function: addGlobalCommentFunction,
      },
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
      newLineNumber,
    }: {
      mergeRequestId: string
      base_sha: string
      head_sha: string
      start_sha: string
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
        gitlabBaseUrl: apiUrl,
        gitlabApiToken: apiKey,
        interactor: this.interactor,
      })
    }

    const addMRThreadTool: FunctionTool<{
      mergeRequestId: string
      base_sha: string
      head_sha: string
      start_sha: string
      oldFilePath: string
      newFilePath: string
      comment: string
      oldLineNumber: number | null
      newLineNumber: number | null
    }> = {
      type: 'function',
      function: {
        name: 'addMRThread',
        description:
          'Use only when reviewing a merge request. Add feedback to a specific change of file of a MR located at a specific line. The file is identified by its old file path and its new file path. The line of code is identified by its old line number and its new line number. For a new line, the old line number should be null and for removed line the new line number should null.',
        parameters: {
          type: 'object',
          properties: {
            mergeRequestId: { type: 'string', description: 'GitLab merge request ID' },
            base_sha: { type: 'string', description: 'Base SHA of the merge request' },
            head_sha: { type: 'string', description: 'Head SHA of the merge request' },
            start_sha: { type: 'string', description: 'Start SHA of the merge request' },
            oldFilePath: { type: 'string', description: 'Path of the file before changes' },
            newFilePath: { type: 'string', description: 'Path of the file after changes' },
            comment: { type: 'string', description: 'Comment to add to the merge request' },
            oldLineNumber: {
              type: ['number', 'null'],
              description: 'Line number in the old file (use null if the line is new)',
            },
            newLineNumber: {
              type: ['number', 'null'],
              description: 'Line number in the new file (use null if the line is removed)',
            },
          },
        },
        parse: JSON.parse,
        function: addMRThreadFunction,
      },
    }

    result.push(retrieveGitlabMRFunction)
    result.push(retrieveGitlabMergeRequestsFunction)
    result.push(retrieveGitlabIssueFunction)
    result.push(retrieveGitlabIssuesFunction)
    result.push(addGlobalCommentTool)
    result.push(addMRThreadTool)

    return result
  }
}
