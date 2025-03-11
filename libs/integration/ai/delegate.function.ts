import { Agent, CommandContext, Interactor } from '../../model'
import { AgentService } from '../../agent'
import { AiThread } from '../../ai-thread/ai-thread'
import { filter, lastValueFrom, Observable, tap } from 'rxjs'
import { CodayEvent, MessageEvent } from '../../shared'

type DelegateInput = {
  context: CommandContext
  interactor: Interactor
  agentService: AgentService
}

export function delegateFunction(input: DelegateInput) {
  const { context, interactor, agentService } = input
  const delegate = async ({ task, agentName }: { task: string; agentName: string | undefined }) => {
    try {
      if (context.stackDepth <= 0) {
        interactor.displayText('Delegation denied further.')
        return 'Delegation not allowed, either permanently, or existing capacity already used.'
      }

      interactor.displayText(`DELEGATING to agent ${agentName} the task:\n${task}`)
      const agent: Agent | undefined = await agentService.findAgentByNameStart(agentName, context)

      if (!agent) {
        const output = `Agent ${agentName} not found.`
        interactor.displayText(output)
        return output
      }
      console.log(`Agent identified: ${agent?.name}`)

      const forkedThread: AiThread = context.aiThread!.fork(agentName ? agent.name : undefined)
      const formattedTask: string = `You were delegated a task to try to complete the best you can.
The parent conversation (all previous messages) is there for context, but your current focus should be on this precise task:

<task>
  ${task}
</task>`

      context.stackDepth--
      const delegatedEvents: Observable<MessageEvent> = (await agent.run(formattedTask, forkedThread)).pipe(
        tap((e) => {
          console.log(`delegated event ${e.type}`)
          let event: CodayEvent = e
          if (e instanceof MessageEvent) {
            event = new MessageEvent({ ...e, name: `-> ${e.name}` })
            // if (!e.name.startsWith("->")) {
            interactor.displayText(e.content, (event as MessageEvent).name)
            // }
          }
          interactor.sendEvent(event)
        }),
        filter((e) => e instanceof MessageEvent)
      )

      // Wait for the completion of the delegated task
      const result = await lastValueFrom(delegatedEvents)

      context.stackDepth++
      context.aiThread!.merge(forkedThread)
      return result.content
    } catch (error: any) {
      console.error('Error in delegate function:', error)
      interactor.displayText(`Error during delegation: ${error.message}`)
      return `Error during delegation: ${error.message}`
    }
  }
  return delegate
}
