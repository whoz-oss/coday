import {
  Agent,
  AgentSummary,
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  FunctionTool,
  IntegrationConfig,
  Interactor,
} from '@coday/model'
import { ThreadService } from '@coday/service'
import { delegateFunction } from './delegate.function'

type Delegation = {
  agentName: string
  task: string
}

export class DelegateTools extends AssistantToolFactory {
  static readonly TYPE = 'DELEGATE' as const

  constructor(
    interactor: Interactor,
    private agentFind: (nameStart: string | undefined, context: CommandContext) => Promise<Agent | undefined>,
    private agentSummaries: () => AgentSummary[],
    instanceName: string,
    config: IntegrationConfig,
    private threadService: ThreadService
  ) {
    super(interactor, instanceName, config)
  }

  /**
   * getTools override: treats 'toolNames' as allow-list of agents, passes to buildTools.
   */
  override async getTools(context: CommandContext, toolNames: string[], agentName: string): Promise<CodayTool[]> {
    return this.buildTools(context, agentName, toolNames)
  }

  /**
   * @param context
   * @param _agentName not used for this integration
   * @param allowedAgentNames (optional) if provided, only these agent names can be delegated to
   */
  protected async buildTools(
    context: CommandContext,
    _agentName: string,
    allowedAgentNames?: string[]
  ): Promise<CodayTool[]> {
    const allowList =
      allowedAgentNames && allowedAgentNames.length > 0
        ? allowedAgentNames.map((name) => name.trim().toLowerCase()).filter(Boolean)
        : undefined

    // List all agent summaries, filter by allow-list if present
    const allAgentSummaries = this.agentSummaries()
    const agentSummaries = allowList
      ? allAgentSummaries.filter((a) => allowList.includes(a.name.toLowerCase()))
      : allAgentSummaries

    // Build the tool description only with allowed agents
    const agentListText = agentSummaries.map((a) => `  - ${a.name} : ${a.description}`).join('\n')

    // Build the delegate function
    const delegate = delegateFunction({
      context,
      interactor: this.interactor,
      agentFind: this.agentFind,
      threadService: this.threadService,
    })

    const delegateWithAllowList = async ({ delegations }: { delegations: Delegation[] }) => {
      // Enforce allow-list per delegation
      if (allowList) {
        const denied = delegations.filter((d) => !allowList.includes(d.agentName.toLowerCase()))
        if (denied.length > 0) {
          const names = denied.map((d) => d.agentName).join(', ')
          const msg = `Delegation denied: agent(s) '${names}' are not allowed for delegation.`
          this.interactor.displayText(msg)
          return msg
        }
      }
      return delegate({ delegations })
    }

    const delegateTool: FunctionTool<{ delegations: Delegation[] }> = {
      type: 'function',
      function: {
        name: `${this.name}__delegate`,
        description: `Delegate one or more tasks to available agents, running them in parallel. Available agents:
            ${agentListText || '(No allowed agents for delegation)'}
            
            Each delegation runs in an isolated sub-thread with clean context (no parent conversation history).
            Task descriptions must be exhaustive and self-contained — include all context, constraints, and requirements.
            Delegations execute in parallel; results are aggregated and returned.
            
            IMPORTANT: The delegated agents will perform ALL actions required (file operations, git, etc.).
            Assess the results and call again if needed — agents maintain their own isolated context across calls.
`,
        parameters: {
          type: 'object',
          properties: {
            delegations: {
              type: 'array',
              description: 'Array of task delegations to execute in parallel.',
              items: {
                type: 'object',
                properties: {
                  agentName: {
                    type: 'string',
                    description: 'Name of the agent to delegate to. Must match an agent in the available list.',
                  },
                  task: {
                    type: 'string',
                    description: `Self-contained task description including:
                      - Intent and objectives
                      - All relevant context and background
                      - Constraints and requirements
                      - Definition of done
                      - Any file paths, references, or data needed
                      
                      Rephrase as if you are the originator of the task.`,
                  },
                },
                required: ['agentName', 'task'],
              },
            },
          },
        },
        parse: JSON.parse,
        function: delegateWithAllowList,
      },
    }

    return [delegateTool]
  }
}
