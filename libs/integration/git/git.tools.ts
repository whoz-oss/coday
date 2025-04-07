import { IntegrationService } from '../../service/integration.service'
import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { git } from './git'

export class GitTools extends AssistantToolFactory {
  name = 'GIT'

  constructor(
    interactor: Interactor,
    private integrationService: IntegrationService
  ) {
    super(interactor)
  }

  protected hasChanged(context: CommandContext): boolean {
    return this.lastToolInitContext?.project.root !== context.project.root
  }

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
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
