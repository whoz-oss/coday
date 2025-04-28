import { CommandContext, Interactor } from '../../model'
import { AgentService } from '../../agent'

type RedirectInput = {
  context: CommandContext
  interactor: Interactor
  agentService: AgentService
}

export function redirectFunction(input: RedirectInput) {
  const { context, interactor } = input
  
  const redirect = ({ query, agentName }: { query: string; agentName: string }) => {
    try {
      interactor.displayText(`REDIRECTING query to agent ${agentName}:\n${query}`)
      
      // Add a command to the queue that will be processed after this run completes
      // The format is "@agentName query" which is the standard format for agent invocation
      const command = `@${agentName} ${query}`
      context.addCommands(command)
      
      return `Query successfully redirected to agent '${agentName}'. The agent will process the query with full context after this run completes.`
    } catch (error: any) {
      console.error('Error in redirect function:', error)
      interactor.displayText(`Error during redirection: ${error.message}`)
      return `Error during redirection: ${error.message}`
    }
  }
  
  return redirect
}