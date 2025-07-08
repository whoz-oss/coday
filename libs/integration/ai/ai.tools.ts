import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { AgentService } from '../../agent'
import { redirectFunction } from './redirect.function'

export class AiTools extends AssistantToolFactory {
  name = 'AI'

  constructor(
    interactor: Interactor,
    private agentService: AgentService
  ) {
    super(interactor)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    if (!context.oneshot) {
      const queryUser = async ({ message, options }: { message: string; options?: string[] }) => {
        const userAnswer = options?.length
          ? await this.interactor.chooseOption(options, message)
          : await this.interactor.promptText(message)
        return `User answered: ${userAnswer}`
      }

      const queryUserTool: FunctionTool<{ message: string }> = {
        type: 'function',
        function: {
          name: 'queryUser',
          description: `Allows to ask the user a question.
IMPORTANT: Use this tool only when necessary, as it is intrusive for the user.

If no options are provided, the user can answer with free text.
If options are provided, the user will have to choose a single option.

AVOID closed options unless the user explicitly needs to choose between specific technical alternatives (like file selection, configuration choices, etc.). Prefer open-ended questions to allow natural, nuanced responses.`,

          parameters: {
            type: 'object',
            properties: {
              message: {
                type: 'string',
                description: 'The query to be added to the queue for user answer.',
              },
              options: {
                type: 'array',
                items: {
                  type: 'string',
                  description:
                    'Optional: list of values for the user to choose one. Use ONLY for technical choices where specific options are required (file selection, configuration values, yes/no decisions). AVOID for general conversation - let users respond naturally with free text instead.',
                },
              },
            },
          },
          parse: JSON.parse,
          function: queryUser,
        },
      }
      result.push(queryUserTool)
    }

    if (!context.oneshot) {
      // Add redirect tool
      const redirect = redirectFunction({ context, agentService: this.agentService })

      const agentSummaries = this.agentService
        .listAgentSummaries()
        .map((a) => `  - ${a.name} : ${a.description}`)
        .join('\n')
      const redirectTool: FunctionTool<{ query: string; agentName: string }> = {
        type: 'function',
        function: {
          name: 'redirect',
          description: `Redirect the current query to another available agent among:
${agentSummaries}

This tool allows you to select a different agent to handle the user's request when another agent is better suited for the task.

Use this when:
- The request clearly falls under another agent's specialty
- You recognize a query pattern that another agent handles better
- The user's intent would be better served by a different agent's capabilities

The redirected agent will run after this conversation completes and will have access to the full conversation history.
`,
          parameters: {
            type: 'object',
            properties: {
              agentName: {
                type: 'string',
                description:
                  'Name of the agent to redirect to. Required. Should be selected based on which agent is most appropriate for the query.',
              },
              query: {
                type: 'string',
                description:
                  "The query to redirect to the selected agent. This should capture the user's intent and any necessary context.",
              },
            },
          },
          parse: JSON.parse,
          function: redirect,
        },
      }
      result.push(redirectTool)
    }

    return result
  }
}
