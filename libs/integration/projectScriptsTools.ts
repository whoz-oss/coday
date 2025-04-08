import { runBash } from '../function/run-bash'
import { CommandContext, Interactor, Scripts } from '../model'
import { AssistantToolFactory, CodayTool } from './assistant-tool-factory'
import { FunctionTool } from './types'

const PARAMETERS: string = 'PARAMETERS'

export class ProjectScriptsTools extends AssistantToolFactory {
  name = 'PROJECT_SCRIPTS'

  constructor(interactor: Interactor) {
    super(interactor)
  }

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
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
              requireConfirmation: false,
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
