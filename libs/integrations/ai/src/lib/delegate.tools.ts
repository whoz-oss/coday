import { AssistantToolFactory } from '@coday/integration'
import { Interactor } from '@coday/model'
import { AgentService } from '@coday/agent'
import { CommandContext } from '@coday/handler'
import { CodayTool } from '@coday/model'
import { delegateFunction } from './delegate.function'
import { FunctionTool } from '@coday/model'

export class DelegateTools extends AssistantToolFactory {
  name = 'DELEGATE'

  constructor(
    interactor: Interactor,
    private agentService: AgentService
  ) {
    super(interactor)
  }

  /**
   * getTools override: treats 'toolNames' as allow-list of agents, passes to buildTools.
   */
  async getTools(context: CommandContext, toolNames: string[], agentName: string): Promise<CodayTool[]> {
    return this.buildTools(context, agentName, toolNames)
  }

  /**
   * @param context
   * @param agentName
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
    const allAgentSummaries = this.agentService.listAgentSummaries()
    const agentSummaries = allowList
      ? allAgentSummaries.filter((a) => allowList.includes(a.name.toLowerCase()))
      : allAgentSummaries

    // Build the tool description only with allowed agents
    const agentListText = agentSummaries.map((a) => `  - ${a.name} : ${a.description}`).join('\n')

    // Check if clean context mode is enabled
    const cleanContextMode = process.env.CODAY_DELEGATE_CLEAN_CONTEXT === 'true'

    // Wrap delegate function to enforce allow-list at call time
    const delegate = delegateFunction({
      context,
      interactor: this.interactor,
      agentService: this.agentService,
    })
    const delegateWithAllowList = async ({ task, agentName }: { task: string; agentName: string | undefined }) => {
      if (allowList && (!agentName || !allowList.includes(agentName.toLowerCase()))) {
        this.interactor.debug(`allowList: ${allowList.join(', ')}`)
        const msg = agentName
          ? `Delegation denied: agent '${agentName}' is not allowed for delegation.`
          : `Delegation denied: no agent selected and delegation is restricted.`
        // Keep as displayText as this is an important user-facing error message
        this.interactor.displayText(msg)
        return msg
      }
      return delegate({ task, agentName })
    }

    const delegateTool: FunctionTool<{ task: string; agentName: string | undefined }> = {
      type: 'function',
      function: {
        name: 'delegate',
        description: cleanContextMode
          ? `Delegate the completion of a task to another available agent among:
${agentListText || '(No allowed agents for delegation)'}

⚠️ CLEAN CONTEXT MODE ACTIVE: The delegated agent will start with a COMPLETELY EMPTY thread.
It will NOT see ANY previous messages, context, or history from this conversation.

YOU MUST provide an EXHAUSTIVE and SELF-CONTAINED task description including:
- Complete background and context of the situation
- All relevant information discussed so far in this conversation
- Precise requirements and constraints
- Expected deliverables and definition of done
- Any code, data, file paths, or references needed
- ALL actions the agent should perform (git operations, file management, etc.)

IMPORTANT: This is a full delegation - the selected agent will have access to its own set of tools and capabilities to complete the entire task. The agent should perform ALL actions required.

These agents are LLM-based, so you should assess in return if the task was correctly executed, and call again the agent if not sufficient or need to adapt. Agents can be called again and will maintain their own isolated context across calls.
`
          : `Delegate the completion of a task to another available agent among:
${agentListText || '(No allowed agents for delegation)'}

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
                'Optional: name of the agent to target. Selects default agent if missing, fails if name is not matching. Only agents in the allow-list are valid.',
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
        function: delegateWithAllowList,
      },
    }

    return [delegateTool]
  }
}
