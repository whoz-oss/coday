import { CommandContext } from '@coday/model'

export function redirectFunction(context: CommandContext, currentAgentName?: string) {
  return ({ query, agentName }: { query: string; agentName: string }) => {
    try {
      // Prevent self-redirection: an agent redirecting to itself would cause an infinite loop
      if (currentAgentName && agentName.toLowerCase() === currentAgentName.toLowerCase()) {
        return `Redirection to self ('${agentName}') is not allowed. Please answer the query directly instead of redirecting to yourself.`
      }

      // Add a command to the queue that will be processed after this run completes
      // The format is "@agentName query" which is the standard format for agent invocation
      const command = `@${agentName} ${query}`
      context.addCommands(command)

      return `Query successfully redirected to agent '${agentName}'. The agent will process the query with full context after this run completes.`
    } catch (error: any) {
      return `Error during redirection: ${error.message}`
    }
  }
}
