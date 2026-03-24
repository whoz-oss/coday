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
import { Delegation } from './delegate.types'

type DelegateInput = {
  context: CommandContext
  interactor: Interactor
  agentFind: (agentName: string | undefined, context: CommandContext) => Promise<Agent | undefined>
  threadService: ThreadService
}

export function delegateFunction(input: DelegateInput) {
  const { context, interactor, agentFind, threadService } = input

  return async ({ delegations }: { delegations: Delegation[] }, thread?: AiThread) => {
    // Use the thread passed by ToolSet.run() — this is the thread the calling agent
    // is actually running in, which is always the correct parent for sub-threads.
    // Falls back to context.aiThread for backward compatibility (root-level calls).
    const parentThread = thread ?? context.aiThread
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

    const asyncDelegations = delegations.filter((d) => d.async)
    const syncDelegations = delegations.filter((d) => !d.async)

    // Launch async delegations immediately — no stackDepth accounting, fire-and-forget
    const asyncResults = asyncDelegations.map((delegation) => {
      const threadId = launchAsyncDelegation(
        delegation,
        context,
        parentThread,
        interactor,
        agentFind,
        threadService,
        projectName
      )
      return { agentName: delegation.agentName, threadId }
    })

    // Run sync delegations in parallel, bracketed by stackDepth
    let syncResults: PromiseSettledResult<{ result: string; threadId: string }>[] = []
    if (syncDelegations.length > 0) {
      context.stackDepth--
      syncResults = await Promise.allSettled(
        syncDelegations.map((delegation) =>
          runSingleDelegation(delegation, context, parentThread, interactor, agentFind, threadService, projectName)
        )
      )
      context.stackDepth++
    }

    // Aggregate results
    const syncOutput = syncResults.map((result, i) => {
      const agentName = syncDelegations[i]?.agentName ?? 'unknown'
      if (result.status === 'fulfilled') {
        const { result: text, threadId } = result.value
        return `[${agentName}] (threadId: ${threadId}): ${text}`
      }
      return `[${agentName}]: Error \u2014 ${result.reason?.message ?? result.reason}`
    })

    const asyncOutput = asyncResults.map(
      ({ agentName, threadId }) =>
        `[${agentName}] (threadId: ${threadId}): launched asynchronously, use list_sub_threads to check progress`
    )

    return [...syncOutput, ...asyncOutput].join('\n\n---\n\n')
  }
}

/**
 * Fire-and-forget async delegation: pre-generates the sub-thread ID (so the caller
 * can return it immediately), then runs the agent in the background.
 * Returns the sub-thread ID synchronously.
 */
function launchAsyncDelegation(
  delegation: Delegation,
  context: CommandContext,
  parentThread: AiThread,
  interactor: Interactor,
  agentFind: (agentName: string | undefined, context: CommandContext) => Promise<Agent | undefined>,
  threadService: ThreadService,
  projectName: string
): string {
  // Pre-generate the sub-thread ID so we can return it before the async work starts
  const preGeneratedThreadId = delegation.threadId ?? crypto.randomUUID()

  // We intentionally do not await this — errors are logged but not propagated
  setupAndRunAsync(
    { ...delegation, threadId: preGeneratedThreadId },
    context,
    parentThread,
    interactor,
    agentFind,
    threadService,
    projectName
  ).catch((error) => {
    interactor.error(`Async delegation to '${delegation.agentName}' failed to launch: ${error?.message ?? error}`)
  })

  return preGeneratedThreadId
}

async function setupAndRunAsync(
  delegation: Delegation & { threadId: string },
  context: CommandContext,
  parentThread: AiThread,
  interactor: Interactor,
  agentFind: (agentName: string | undefined, context: CommandContext) => Promise<Agent | undefined>,
  threadService: ThreadService,
  projectName: string
): Promise<void> {
  const { agentName, task } = delegation
  const parentEventId = new Date().toISOString()

  let subThread: AiThread

  // Check if resuming an existing thread or creating a new one with the pre-generated ID
  const existingThread = await threadService.getThread(projectName, delegation.threadId)
  if (existingThread) {
    subThread = existingThread
    subThread.delegationDepth = parentThread.delegationDepth + 1
    subThread.runStatus = RunStatus.RUNNING
  } else {
    subThread = parentThread.fork(agentName, task, parentEventId)
    // Override the random ID from fork() with the pre-generated one so the caller's threadId is valid
    subThread.id = delegation.threadId
    try {
      await threadService.saveThread(projectName, subThread)
    } catch (err) {
      interactor.debug(`Could not persist async sub-thread ${subThread.id}: ${err}`)
    }
  }

  const delegationEvent = new DelegationEvent({
    subThreadId: subThread.id,
    agentName,
    threadId: parentThread.id,
  })
  parentThread.addDelegationEvent(delegationEvent)
  interactor.sendEvent(delegationEvent)

  const agent: Agent | undefined = await agentFind(agentName, context)
  if (!agent) {
    interactor.error(`Async delegation: agent '${agentName}' not found.`)
    return
  }

  interactor.sendEvent(new AnswerEvent({ answer: task, threadId: subThread.id }))

  // Stop propagation still runs in background
  const stopPropagationInterval = setInterval(() => {
    if (parentThread.runStatus === RunStatus.STOPPED && subThread.runStatus !== RunStatus.STOPPED) {
      interactor.debug(`Propagating stop signal to async sub-thread ${subThread.id}`)
      subThread.runStatus = RunStatus.STOPPED
    }
  }, 1000)

  try {
    const agentEvents: Observable<MessageEvent> = (await agent.run(task, subThread)).pipe(
      filter((e: unknown) => e instanceof MessageEvent)
    )
    await lastValueFrom(agentEvents, { defaultValue: undefined })

    try {
      await threadService.saveThread(projectName, subThread)
    } catch (err) {
      interactor.debug(`Could not persist async sub-thread ${subThread.id}: ${err}`)
    }

    // Merge price from sub-thread back to parent.
    // We reload the parent thread from disk to avoid writing into a stale in-memory object
    // (the parent may have been serialized to disk already while the sub-thread was running).
    // If reload fails, we fall back to the in-memory merge to avoid losing data silently.
    try {
      const freshParent = await threadService.getThread(projectName, parentThread.id)
      if (freshParent) {
        freshParent.merge(subThread)
        await threadService.saveThread(projectName, freshParent)
      } else {
        // Parent thread not found on disk (e.g. root thread not yet persisted) — merge in-memory only
        parentThread.merge(subThread)
      }
    } catch (mergeErr) {
      // On error, fall back to in-memory merge so tokens are at least visible for the current session
      parentThread.merge(subThread)
      interactor.debug(`Could not persist price merge for parent thread ${parentThread.id}: ${mergeErr}`)
    }
  } catch (error: any) {
    interactor.error(`Async delegation to '${agentName}' failed: ${error?.message ?? error}`)
  } finally {
    clearInterval(stopPropagationInterval)
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
): Promise<{ result: string; threadId: string }> {
  const { agentName, task } = delegation

  // Use a placeholder parentEventId — in a full implementation this would be the ToolRequestEvent timestamp
  const parentEventId = new Date().toISOString()

  let subThread: AiThread
  let isResumedThread = false

  if (delegation.threadId) {
    // Resume an existing thread
    const existingThread = await threadService.getThread(projectName, delegation.threadId)
    if (!existingThread) {
      return {
        result: `Thread '${delegation.threadId}' not found in project '${projectName}'.`,
        threadId: delegation.threadId,
      }
    }
    subThread = existingThread
    isResumedThread = true
    // Set runtime-only fields for this execution
    subThread.delegationDepth = parentThread.delegationDepth + 1
    subThread.runStatus = RunStatus.RUNNING
  } else {
    // Create a new sub-thread (existing behavior)
    subThread = parentThread.fork(agentName, task, parentEventId)
  }

  if (!isResumedThread) {
    // Persist the sub-thread immediately so it appears in the thread list
    try {
      await threadService.saveThread(projectName, subThread)
    } catch (err) {
      interactor.debug(`Could not persist sub-thread ${subThread.id}: ${err}`)
    }
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
    return { result: `Agent ${agentName} not found.`, threadId: subThread.id }
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
    // Emit the task as an AnswerEvent tagged with the sub-thread ID so the
    // DelegationInlineComponent can show what was asked to the agent.
    // agent.run() adds it to the thread history but does NOT broadcast it.
    interactor.sendEvent(
      new AnswerEvent({
        answer: task,
        threadId: subThread.id,
        name: context.username,
      })
    )

    // Run the agent — the AI client will pass subThread to ToolSet.run(),
    // so any nested delegation from this agent will correctly use subThread as parent.
    const agentEvents: Observable<MessageEvent> = (await agent.run(task, subThread, context.username)).pipe(
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

    // Merge price from sub-thread back to parent.
    // We reload the parent thread from disk to avoid writing into a stale in-memory object
    // (the parent may have been serialized to disk already while the sub-thread was running).
    // If reload fails, we fall back to the in-memory merge to avoid losing data silently.
    try {
      const freshParent = await threadService.getThread(projectName, parentThread.id)
      if (freshParent) {
        freshParent.merge(subThread)
        await threadService.saveThread(projectName, freshParent)
      } else {
        // Parent thread not found on disk (e.g. root thread not yet persisted) — merge in-memory only
        parentThread.merge(subThread)
      }
    } catch (mergeErr) {
      // On error, fall back to in-memory merge so tokens are at least visible for the current session
      parentThread.merge(subThread)
      interactor.debug(`Could not persist price merge for parent thread ${parentThread.id}: ${mergeErr}`)
    }
  } catch (error: any) {
    result = `Error during delegation: ${error.message}`
    console.error(`Error in delegation for agent ${agentName}:`, error)
    interactor.error(result)
  } finally {
    clearInterval(stopPropagationInterval)
  }

  return { result, threadId: subThread.id }
}
