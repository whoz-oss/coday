import {
  Agent,
  AiThread,
  AnswerEvent,
  CommandContext,
  DelegationEvent,
  Interactor,
  MessageEvent,
  RunStatus,
} from '@coday/model'
import { ThreadService } from '@coday/service'
import { lastValueFrom, Observable } from 'rxjs'
import { filter } from 'rxjs/operators'

type Delegation = {
  agentName: string
  task: string
}

type DelegateInput = {
  context: CommandContext
  interactor: Interactor
  agentFind: (agentName: string | undefined, context: CommandContext) => Promise<Agent | undefined>
  threadService: ThreadService
}

export function delegateFunction(input: DelegateInput) {
  const { context, interactor, agentFind, threadService } = input

  return async ({ delegations }: { delegations: Delegation[] }) => {
    // Use currentThread (top of stack) instead of aiThread (always root)
    const parentThread = context.currentThread
    interactor.debug(`Delegating ${delegations.length} task(s) with stackDepth: ${context.stackDepth}`)

    if (context.stackDepth <= 0) {
      return 'Delegation not allowed, either permanently, or existing capacity already used.'
    }

    if (!delegations || delegations.length === 0) {
      return 'No delegations provided.'
    }

    if (!parentThread) {
      return 'No active thread for delegation.'
    }

    const projectName = context.project.name

    // Decrement stack depth once for the entire parallel batch
    context.stackDepth--

    // Run all delegations in parallel
    const results = await Promise.allSettled(
      delegations.map((delegation) =>
        runSingleDelegation(delegation, context, parentThread, interactor, agentFind, threadService, projectName)
      )
    )

    // Restore stack depth after all delegations complete
    context.stackDepth++

    // Aggregate results
    return results
      .map((result, i) => {
        const agentName = delegations[i]?.agentName ?? 'unknown'
        if (result.status === 'fulfilled') {
          return `[${agentName}]: ${result.value}`
        }
        return `[${agentName}]: Error — ${result.reason?.message ?? result.reason}`
      })
      .join('\n\n---\n\n')
  }
}

async function runSingleDelegation(
  delegation: Delegation,
  context: CommandContext,
  parentThread: AiThread,
  interactor: Interactor,
  agentFind: (agentName: string | undefined, context: CommandContext) => Promise<Agent | undefined>,
  threadService: ThreadService,
  projectName: string
): Promise<string> {
  const { agentName, task } = delegation

  // Use a placeholder parentEventId — in a full implementation this would be the ToolRequestEvent timestamp
  const parentEventId = new Date().toISOString()

  // Create the sub-thread from the CURRENT parent thread (not always root)
  const subThread: AiThread = parentThread.fork(agentName, task, parentEventId)

  // Persist the sub-thread immediately so it appears in the thread list
  try {
    await threadService.saveThread(projectName, subThread)
  } catch (err) {
    interactor.debug(`Could not persist sub-thread ${subThread.id}: ${err}`)
  }

  // Emit a single, immutable DelegationEvent as a branch marker.
  // Tag it with the parent thread's ID so the frontend routes it correctly:
  // - Root-level delegations: threadId is undefined/root → displayed in main chat
  // - Nested delegations: threadId is the parent sub-thread → routed to the
  //   parent's DelegationInlineComponent via subThreadEvents$
  const delegationEvent = new DelegationEvent({
    subThreadId: subThread.id,
    agentName,
    threadId: parentThread.id,
  })
  parentThread.addDelegationEvent(delegationEvent)
  interactor.sendEvent(delegationEvent)

  const agent: Agent | undefined = await agentFind(agentName, context)
  if (!agent) {
    return `Agent ${agentName} not found.`
  }

  // Propagate stop signal from parent thread to sub-thread
  const stopPropagationInterval = setInterval(() => {
    if (parentThread.runStatus === RunStatus.STOPPED && subThread.runStatus !== RunStatus.STOPPED) {
      interactor.debug(`Propagating stop signal to sub-thread ${subThread.id}`)
      subThread.runStatus = RunStatus.STOPPED
    }
  }, 1000)

  let result: string = 'Delegation did not produce a result.'

  try {
    // Push the sub-thread onto the context stack so that any nested
    // delegation from this agent correctly forks from the sub-thread.
    context.pushThread(subThread)

    // Emit the task as an AnswerEvent tagged with the sub-thread ID so the
    // DelegationInlineComponent can show what was asked to the agent.
    // agent.run() adds it to the thread history but does NOT broadcast it.
    interactor.sendEvent(
      new AnswerEvent({
        answer: task,
        threadId: subThread.id,
      })
    )

    // Run the agent — sub-thread events are NOT forwarded to parent interactor
    // We only filter for the final MessageEvent result
    const agentEvents: Observable<MessageEvent> = (await agent.run(task, subThread)).pipe(
      filter((e) => e instanceof MessageEvent)
    )

    const lastMessage: MessageEvent | undefined = await lastValueFrom(agentEvents, { defaultValue: undefined })

    if (subThread.runStatus === RunStatus.STOPPED) {
      result = 'Delegation was interrupted before completion. No result available.'
    } else if (!lastMessage) {
      result = 'Delegation completed but produced no message.'
    } else {
      result = lastMessage.getTextContent()
    }

    // Persist final sub-thread state
    try {
      await threadService.saveThread(projectName, subThread)
    } catch (err) {
      interactor.debug(`Could not persist final sub-thread ${subThread.id}: ${err}`)
    }

    // Merge price from sub-thread back to parent
    parentThread.merge(subThread)
  } catch (error: any) {
    result = `Error during delegation: ${error.message}`
    console.error(`Error in delegation for agent ${agentName}:`, error)
    interactor.error(result)
  } finally {
    // Always pop the sub-thread from the stack, even on error
    context.popThread()
    clearInterval(stopPropagationInterval)
  }

  return result
}
