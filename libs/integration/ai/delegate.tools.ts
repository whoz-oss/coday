import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { AgentService } from '../../agent'
import { delegateFunction } from './delegate.function'

export class DelegateTools extends AssistantToolFactory {
  name = 'Delegate'

  constructor(
    interactor: Interactor,
    private agentService: AgentService
  ) {
    super(interactor)
  }

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    const delegate = delegateFunction({ context, interactor: this.interactor, agentService: this.agentService })

    const agentSummaries = this.agentService
      .listAgentSummaries()
      .map((a) => `  - ${a.name} : ${a.description}`)
      .join('\n')
    const delegateTool: FunctionTool<{ task: string; agentName: string | undefined }> = {
      type: 'function',
      function: {
        name: 'delegate',
        description: `Delegate the completion of a task to another available agent among:
${agentSummaries}

IMPORTANT: This is a full delegation - the selected agent will have access to its own set of tools and capabilities to complete the entire task. Do not split the task between yourself and the delegated agent. The agent should perform ALL actions required, including using any tools it has access to (git operations, file management, etc.).

THREAD ISOLATION: The delegated agent works in an isolated thread with limited context. It will only see previous interactions with itself, not recent messages between you and the user. This means you must include ALL necessary context in your task description - don't assume the agent can see what you and the user just discussed.

These agents are LLM-based, so you should assess in return if the task was correctly executed, and call again the agent if not sufficient or need to adapt. Agents can be called again without losing their context if more information is needed.
`,
        parameters: {
          type: 'object',
          properties: {
            agentName: {
              type: 'string',
              description:
                'Optional: name of the agent to target. Selects default agent if missing, fails if name is not matching. Recommended to select one fit for the task for relevant results.',
            },
            task: {
              type: 'string',
              description: `Description of the task to delegate, should contain:
              
  - intent
  - constraints
  - definition of done
  - any necessary actions/operations to perform (the agent will execute these directly)
  
  Take care to rephrase it as if you are the originator of the task. Include ALL required actions in the task description - do not expect to perform any actions yourself after delegation.
                `,
            },
          },
        },
        parse: JSON.parse,
        function: delegate,
      },
    }
    result.push(delegateTool)

    return result
  }
}