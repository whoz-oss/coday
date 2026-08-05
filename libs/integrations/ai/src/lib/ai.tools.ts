import { AgentSummary, CommandContext } from '@coday/model'
import { Interactor } from '@coday/model'
import { AssistantToolFactory } from '@coday/model'
import { CodayTool } from '@coday/model'
import { FunctionTool } from '@coday/model'
import { IntegrationConfig } from '@coday/model'
import { redirectFunction } from './redirect.function'

/**
 * Build the redirect tool for the given agent summaries.
 * Returns undefined when summaries is empty (no redirect tool should be exposed).
 *
 * Extracted as a pure exported function so that Toolbox can call it directly
 * with pre-filtered summaries, keeping all delegation-vs-redirect logic out of
 * this class (invariant b: AiTools has no knowledge of DelegateTools).
 */
export function buildRedirectTool(
  factoryName: string,
  context: CommandContext,
  agentName: string,
  summaries: AgentSummary[]
): FunctionTool<{ query: string; agentName: string }> | undefined {
  if (summaries.length === 0) return undefined

  const redirect = redirectFunction(context, agentName)
  const agentSummariesText = summaries.map((a) => `  - ${a.name} : ${a.description}`).join('\n')

  return {
    type: 'function',
    function: {
      name: `${factoryName}__redirect`,
      description: `Redirect the current query to another available agent among:\n${agentSummariesText}\n\nThis tool allows you to select a different agent to handle the user's request when another agent is better suited for the task.\n\nUse this when:\n- The request clearly falls under another agent's specialty\n- You recognize a query pattern that another agent handles better\n- The user's intent would be better served by a different agent's capabilities\n\nThe redirected agent will run after this conversation completes and will have access to the full conversation history.\n`,
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
}

export class AiTools extends AssistantToolFactory {
  static readonly TYPE = 'AI' as const

  constructor(
    interactor: Interactor,
    private agentSummaries: () => AgentSummary[],
    instanceName: string,
    config: IntegrationConfig
  ) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    if (!context.oneshot) {
      const queryUser = async ({ message, options }: { message: string; options?: string[] }) => {
        const userAnswer = options?.length
          ? await this.interactor.chooseOption(options, message, undefined, true)
          : await this.interactor.promptText(message)
        return `User answered: ${userAnswer}`
      }

      const queryUserTool: FunctionTool<{ message: string }> = {
        type: 'function',
        function: {
          name: `${this.name}__queryUser`,
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
      // Build redirect tool with the full agent list — Toolbox will replace this tool
      // with a filtered version when a DELEGATE integration is present.
      const redirectTool = buildRedirectTool(this.name, context, agentName, this.agentSummaries())
      if (redirectTool) {
        result.push(redirectTool)
      }
    }

    return result
  }
}
