import { AgentService } from '@coday/agent'
import { CommandContext } from '@coday/handler'

type RedirectInput = {
  context: CommandContext
  agentService: AgentService
}

export function redirectFunction(input: RedirectInput) {
  const { context } = input

  const redirect = ({ query, agentName }: { query: string; agentName: string }) => {
    try {
      // Add a command to the queue that will be processed after this run completes
      // The format is "@agentName query" which is the standard format for agent invocation
      const command = `@${agentName} ${query}`
      context.addCommands(command)

      return `Query successfully redirected to agent '${agentName}'. The agent will process the query with full context after this run completes.`
    } catch (error: any) {
      return `Error during redirection: ${error.message}`
    }
  }

  return redirect
}
