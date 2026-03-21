import {
  Agent,
  AgentSummary,
  AiThread,
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
  threadId?: string
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
    const projectName = context.project.name
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

    const delegateWithAllowList = async ({ delegations }: { delegations: Delegation[] }, thread?: AiThread) => {
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
      return delegate({ delegations }, thread)
    }

    const delegateTool: FunctionTool<{ delegations: Delegation[] }> = {
      type: 'function',
      function: {
        name: `${this.name}__delegate`,
        description: `Delegate one or more tasks to available agents, running them in parallel. Available agents:\n            ${agentListText || '(No allowed agents for delegation)'}\n            \n            Each delegation runs in an isolated sub-thread with clean context (no parent conversation history).\n            Task descriptions must be exhaustive and self-contained — include all context, constraints, and requirements.\n            Delegations execute in parallel; results are aggregated and returned.\n            \n            IMPORTANT: The delegated agents will perform ALL actions required (file operations, git, etc.).\n            Assess the results and call again if needed — agents maintain their own isolated context across calls.\n            To resume a previous delegation: call list_sub_threads first to get the threadId, then pass it here. IMPORTANT: when resuming with a threadId, do NOT copy prior context into the task — the agent already has its full thread history. Only describe what NEW work is needed. The agentName does NOT need to match the original agent — you can direct any agent into an existing thread's context. Do NOT create a new thread when the intent is to continue or extend prior work.\n`,
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
                    description: `Self-contained task description including:\n                      - Intent and objectives\n                      - All relevant context and background\n                      - Constraints and requirements\n                      - Definition of done\n                      - Any file paths, references, or data needed\n                      \n                      Rephrase as if you are the originator of the task.`,
                  },
                  threadId: {
                    type: 'string',
                    description: `Optional: ID of an existing thread to resume. When provided, the named agent runs in that thread's full existing context — do NOT repeat prior work or context in the task, only describe the new work needed. The agent does not need to match the one that originally worked on the thread — any agent can be directed into an existing thread's context. When omitted, a fresh isolated sub-thread is created.`,
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

    const listSubThreadsTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: `${this.name}__list_sub_threads`,
        description: `List all sub-threads previously spawned by delegations from the current thread. Returns threadId, agent name, task summary, and last modified date. Always call this before resuming a delegation — use the returned threadId in the delegate tool instead of re-describing prior context. The agent field shows who originally worked on the thread, but any agent can be asked to resume it.`,
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: async (_args: Record<string, never>, thread?: AiThread) => {
          const parentThread = thread ?? context.aiThread
          if (!parentThread) {
            return 'No active thread context available.'
          }
          try {
            const allThreads = await this.threadService.listThreads(projectName, context.username)
            const subThreads = allThreads.filter((t) => t.parentThreadId === parentThread.id)
            if (!subThreads.length) {
              return 'No sub-threads found for the current thread.'
            }
            return subThreads
              .sort((a, b) => (a.modifiedDate > b.modifiedDate ? -1 : 1))
              .map(
                (t) =>
                  `- threadId: ${t.id}\n  agent: ${t.delegatedAgentName || '(unknown)'}\n  task: ${t.delegatedTask || '(no task)'}\n  modified: ${t.modifiedDate}`
              )
              .join('\n\n')
          } catch (error) {
            const msg = `Failed to list sub-threads: ${error instanceof Error ? error.message : 'Unknown error'}`
            this.interactor.error(msg)
            return msg
          }
        },
      },
    }

    return [delegateTool, listSubThreadsTool]
  }
}
