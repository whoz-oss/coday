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

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    if (!context.oneshot) {
      const queryUser = ({ message }: { message: string }) => {
        const command = `add-query ${message}`
        context.addCommands(command)
        return 'Query successfully queued, user will maybe answer later.'
      }

      const queryUserTool: FunctionTool<{ message: string }> = {
        type: 'function',
        function: {
          name: 'queryUser',
          description:
            'Queues asynchronously a query (question or request) for the user who may answer later, after this current run. IMPORTANT: Use this tool only when necessary, as it interrupts the flow of execution to seek user input.',
          parameters: {
            type: 'object',
            properties: {
              message: {
                type: 'string',
                description: 'The query to be added to the queue for user answer.',
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
      const redirect = redirectFunction({ context, interactor: this.interactor, agentService: this.agentService })

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

This tool allows you to select a different agent to handle the user's request when you believe another agent is better suited for the task. Unlike delegation, redirection queues a command to run the target agent with the full conversation context.

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
