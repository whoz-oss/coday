import { IntegrationService } from '@coday/service/integration.service'
import { AssistantToolFactory } from '@coday/integration/assistant-tool-factory'
import { Interactor } from '@coday/model/interactor'
import { CommandContext } from '@coday/handler'
import { CodayTool } from '@coday/model/coday-tool'
import { git } from './git'
import { FunctionTool } from '@coday/model/integration-types'

export class GitTools extends AssistantToolFactory {
  name = 'GIT'

  constructor(
    interactor: Interactor,
    private integrationService: IntegrationService
  ) {
    super(interactor)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    if (!this.integrationService.hasIntegration('GIT')) {
      return result
    }

    const gitFunction = async ({ params }: { params: string }) => {
      return await git({
        params,
        root: context.project.root,
        interactor: this.interactor,
      })
    }

    const gitTool: FunctionTool<{ params: string }> = {
      type: 'function',
      function: {
        name: 'git',
        description: 'Run git command and parameters.',
        parameters: {
          type: 'object',
          properties: {
            params: {
              type: 'string',
              description:
                'Additional command (like add, log, diff, status) and parameters for git, will be composed as `git [params]`.',
            },
          },
        },
        parse: JSON.parse,
        function: gitFunction,
      },
    }

    result.push(gitTool)

    return result
  }
}
