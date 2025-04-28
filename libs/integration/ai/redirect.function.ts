import { CommandContext, Interactor } from '../../model'
import { AgentService } from '../../agent'
import { filter, lastValueFrom, Observable, tap } from 'rxjs'
import { CodayEvent, MessageEvent } from '../../shared'

type RedirectInput = {
  context: CommandContext
  interactor: Interactor
  agentService: AgentService
}

export function redirectFunction(input: RedirectInput) {
  const { context, interactor, agentService } = input
  const redirect = async ({ query, agentName }: { query: string; agentName: string }) => {
    try {
      interactor.displayText(`REDIRECTING to agent ${agentName} the query:\n${query}`)
      const agent = await agentService.findAgentByNameStart(agentName, context)

      if (!agent) {
        const output = `Agent ${agentName} not found.`
        interactor.displayText(output)
        return output
      }
      console.log(`Agent identified for redirect: ${agent?.name}`)

      // Unlike delegation, we pass the full thread context for redirection
      const redirectedEvents: Observable<MessageEvent> = (await agent.run(query, context.aiThread!)).pipe(
        tap((e) => {
          console.log(`redirected event ${e.type}`)
          let event: CodayEvent = e
          if (e instanceof MessageEvent) {
            event = new MessageEvent({ ...e, name: `-> ${e.name}` })
            interactor.displayText(e.content, (event as MessageEvent).name)
          }
          interactor.sendEvent(event)
        }),
        filter((e) => e instanceof MessageEvent)
      )

      // Wait for the completion of the redirected query
      const result = await lastValueFrom(redirectedEvents)
      return result.content
    } catch (error: any) {
      console.error('Error in redirect function:', error)
      interactor.displayText(`Error during redirection: ${error.message}`)
      return `Error during redirection: ${error.message}`
    }
  }
  return redirect
}