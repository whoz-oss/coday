import { AssistantToolFactory } from '@coday/integration/assistant-tool-factory'
import { Interactor } from '@coday/model/interactor'
import { CommandContext } from '@coday/handler'
import { CodayTool } from '@coday/model/coday-tool'
import { Scripts } from '@coday/model/scripts'
import { runBash } from '@coday/function/run-bash'
import { FunctionTool } from '@coday/model/integration-types'

const PARAMETERS: string = 'PARAMETERS'

export class ProjectScriptsTools extends AssistantToolFactory {
  name = 'PROJECT_SCRIPTS'

  constructor(interactor: Interactor) {
    super(interactor)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    const scripts: Scripts | undefined = context.project.scripts
    const scriptFunctions = scripts
      ? Object.entries(scripts).map((entry) => {
          const script = async (params: any) => {
            let commandWithParams
            if (entry[1].command.includes(PARAMETERS)) {
              commandWithParams = entry[1].command.replace(PARAMETERS, params?.stringParameters ?? '')
            } else {
              commandWithParams = `${entry[1].command} ${params?.stringParameters ?? ''}`
            }
            return await runBash({
              command: commandWithParams,
              root: context.project.root,
              interactor: this.interactor,
              requireConfirmation: entry[1].requireConfirmation,
            })
          }
          const scriptFunction: FunctionTool<unknown> = {
            type: 'function',
            function: {
              name: entry[0],
              description: entry[1].description,
              parameters: entry[1].parametersDescription
                ? {
                    type: 'object',
                    properties: {
                      stringParameters: {
                        type: 'string',
                        description: entry[1].parametersDescription,
                      },
                    },
                  }
                : {
                    type: 'object',
                    properties: {},
                  },
              parse: JSON.parse,
              function: script,
            },
          }
          return scriptFunction
        })
      : []

    result.push(...scriptFunctions)

    return result
  }
}
