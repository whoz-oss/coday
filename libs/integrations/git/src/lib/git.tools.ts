import { IntegrationService } from '@coday/service'
import { AssistantToolFactory } from '@coday/model'
import { Interactor } from '@coday/model'
import { CommandContext } from '@coday/model'
import { CodayTool } from '@coday/model'
import { git } from './git'
import { FunctionTool } from '@coday/model'

export class GitTools extends AssistantToolFactory {
  static readonly TYPE = 'GIT' as const

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService,
    instanceName: string,
    config?: any
  ) {
    super(interactor, instanceName, config)
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
        name: `${this.name}`, // should be 'GIT' or as explicit
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
