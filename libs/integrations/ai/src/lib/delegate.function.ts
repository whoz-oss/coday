import { Agent, AiThread, CommandContext, Interactor, MessageEvent, RunStatus } from '@coday/model'
import { lastValueFrom, Observable } from 'rxjs'
import { filter, tap } from 'rxjs/operators'

type DelegateInput = {
  context: CommandContext
  interactor: Interactor
  agentFind: (agentName: string | undefined, context: CommandContext) => Promise<Agent | undefined>
}

export function delegateFunction(input: DelegateInput) {
  const { context, interactor, agentFind } = input
  const delegate = async ({ task, agentName }: { task: string; agentName: string | undefined }) => {
    try {
      interactor.debug(`Delegating with stackDepth: ${context.stackDepth}`)
      if (context.stackDepth <= 0) {
        return 'Delegation not allowed, either permanently, or existing capacity already used.'
      }

      const agent: Agent | undefined = await agentFind(agentName, context)

      if (!agent) {
        return `Agent ${agentName} not found.`
      }

      // Check if clean context mode is enabled via environment variable
      const cleanContextMode = process.env.CODAY_DELEGATE_CLEAN_CONTEXT === 'true'
      interactor.debug(`Delegate clean context mode: ${cleanContextMode}`)

      const forkedThread: AiThread = context.aiThread!.fork(agentName ? agent.name : undefined, cleanContextMode)
      const formattedTask: string = `You were delegated a task to try to complete the best you can.
The parent conversation (all previous messages) is there for context, but your current focus should be on this precise task:

<task>
  ${task}
</task>`

      context.stackDepth--
      const delegatedEvents: Observable<MessageEvent> = (await agent.run(formattedTask, forkedThread)).pipe(
        tap((e) => {
          interactor.sendEvent(e)
        }),
        filter((e) => e instanceof MessageEvent)
      )

      // Propagate stop signal from parent thread to forked thread
      const stopPropagationInterval = setInterval(() => {
        if (context.aiThread!.runStatus === RunStatus.STOPPED && forkedThread.runStatus !== RunStatus.STOPPED) {
          interactor.debug('Propagating stop signal to delegated sub-thread')
          forkedThread.runStatus = RunStatus.STOPPED
        }
      }, 1000)

      // Wait for the completion of the delegated task
      let result: MessageEvent | undefined
      try {
        result = await lastValueFrom(delegatedEvents, { defaultValue: undefined })
      } finally {
        clearInterval(stopPropagationInterval)
        // Always restore stackDepth to avoid corrupting the depth guard on exception
        context.stackDepth++
      }

      if (forkedThread.runStatus === RunStatus.STOPPED) {
        return 'Delegation was interrupted before completion. No result available.'
      }

      // Only merge the forked thread if delegation completed successfully
      context.aiThread!.merge(forkedThread)

      if (!result) {
        return 'Delegation completed but produced no message.'
      }
      return result.content
    } catch (error: any) {
      console.error('Error in delegate function:', error)
      interactor.error(`Error during delegation: ${error.message}`)
      return `Error during delegation: ${error.message}`
    }
  }
  return delegate
}
