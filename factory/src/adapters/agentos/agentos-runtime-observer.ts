/**
 * Polling and quiescence observer for an AgentOS execution.
 *
 * ## Why `CaseStatusEvent` and not `AgentFinishedEvent`
 *
 * `AgentFinishedEvent` means "one agent turn ended". It is emitted in several
 * backend places that are followed by more work without new user input
 * (interrupt/redirection, pending command queue, confirmation gate, awaiting a
 * query answer). Concluding "the phase is finished" on the first of these emits
 * a verdict while work is still happening.
 *
 * The correct signal is `CaseStatusEvent`, emitted after `runTurns()` returns,
 * i.e. after the command queue and the redirection chain are drained.
 * `status ∈ {IDLE, KILLED, ERROR}` is quiescence.
 *
 * ## Two waits, not one
 *
 * The message POST is asynchronous. Between the POST and the RUNNING
 * transition the history contains nothing new. Waiting directly for IDLE would
 * conclude "finished" before work even started. So: wait for RUNNING first,
 * then wait for quiescence after that RUNNING.
 *
 * ## F7 — quiescence after the LAST RUNNING, not the first
 *
 * An agent can chain several turns without new user input. Each intermediate
 * RUNNING→IDLE is a legitimate `CaseStatusEvent` IDLE, but followed by another
 * RUNNING. Stopping on the FIRST IDLE after the FIRST RUNNING would produce a
 * verdict mid-work. At every poll the observer advances `runningIndex` to the
 * most recent RUNNING, then searches for quiescence after it.
 */

import type {
  ExecutionObservation,
  ExecutionStatus,
  ObserveOptions,
  RuntimeFailure,
} from '../../ports/agent-runtime-gateway.js'
import {
  buildFailedToolCalls,
  CASE_STATUS_EVENT,
  collectAgentsSelected,
  collectLlmModels,
  countType,
  extractLastAgentMessage,
  findLastStatusEvent,
  findStatusEvent,
  findUnansweredQuestions,
  QUIESCENT_STATUSES,
  type RawCaseEvent,
  sliceAfterId,
  toRuntimeEvents,
} from './agentos-event-translator.js'

/** Interval between two history polls. */
export const DEFAULT_POLL_INTERVAL_MS = 2_000

/** Default budget to see the execution start (reach RUNNING). */
export const DEFAULT_START_TIMEOUT_MS = 30_000

/** Default budget for the work itself, once started. */
export const DEFAULT_WORK_TIMEOUT_MS = 10 * 60 * 1000

/** Dependencies of the observer; all I/O is injected. */
export interface AgentOsRuntimeObserverDeps {
  listEvents(executionId: string): Promise<RawCaseEvent[]>
  killCase(executionId: string): Promise<void>
  sleep?: (ms: number) => Promise<void>
  now?: () => number
  pollIntervalMs?: number
}

/** Observer surface used by the runtime adapter. */
export interface AgentOsRuntimeObserver {
  observe(executionId: string, options?: ObserveOptions): Promise<ExecutionObservation>
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** Failure report, with zeroed counters rather than absent fields. */
export function executionFailure(
  status: ExecutionStatus,
  message: string,
  extra: Partial<RuntimeFailure> = {}
): ExecutionObservation {
  return {
    status,
    caseStatus: null,
    message,
    events: [],
    agentsSelected: [],
    agentTurns: 0,
    toolCallCount: 0,
    failedToolCalls: {},
    killedByBudget: false,
    anchored: true,
    llmModels: [],
    ...extra,
  }
}

function statusOf(event: RawCaseEvent): string {
  return typeof event.status === 'string' ? event.status : ''
}

/** Builds the result of a finished turn, from the reached quiescence status. */
function buildTurnResult(
  turnEvents: RawCaseEvent[],
  allEvents: RawCaseEvent[],
  quiescentEvent: RawCaseEvent,
  anchored: boolean
): ExecutionObservation {
  const toolResponses = turnEvents.filter((e) => e.type === 'ToolResponseEvent')
  const base = {
    caseStatus: statusOf(quiescentEvent),
    message: extractLastAgentMessage(turnEvents),
    events: toRuntimeEvents(turnEvents),
    agentsSelected: collectAgentsSelected(turnEvents),
    agentTurns: countType(turnEvents, 'AgentFinishedEvent'),
    toolCallCount: toolResponses.length,
    failedToolCalls: buildFailedToolCalls(toolResponses),
    killedByBudget: false,
    anchored,
    llmModels: collectLlmModels(turnEvents),
  }

  const quiescence = statusOf(quiescentEvent)
  if (quiescence === 'KILLED') return { ...base, status: 'killed' }
  if (quiescence === 'ERROR') return { ...base, status: 'case_error' }

  // IDLE: the turn is over, but the agent may be waiting for a human answer.
  const unanswered = findUnansweredQuestions(allEvents)
  const lastQuestion = unanswered.at(-1)
  if (lastQuestion) {
    const question = typeof lastQuestion.question === 'string' ? lastQuestion.question : base.message
    return { ...base, status: 'pending_question', message: question }
  }

  return { ...base, status: 'finished' }
}

/** Builds the polling observer. */
export function createAgentOsRuntimeObserver(deps: AgentOsRuntimeObserverDeps): AgentOsRuntimeObserver {
  const sleep = deps.sleep ?? defaultSleep
  const now = deps.now ?? Date.now
  const pollIntervalMs = deps.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS

  async function killQuietly(executionId: string): Promise<void> {
    try {
      await deps.killCase(executionId)
    } catch {
      // ignored: the failure verdict takes precedence over the kill succeeding
    }
  }

  return {
    async observe(executionId: string, options: ObserveOptions = {}): Promise<ExecutionObservation> {
      const startTimeoutMs = options.startTimeoutMs ?? DEFAULT_START_TIMEOUT_MS
      const workTimeoutMs = options.workTimeoutMs ?? DEFAULT_WORK_TIMEOUT_MS
      const baselineId = options.baselineId ?? null

      const startDeadline = now() + startTimeoutMs
      let workDeadline = 0
      let started = false
      let runningIndex = -1
      let anchored = true

      for (;;) {
        await sleep(pollIntervalMs)

        let allEvents: RawCaseEvent[]
        try {
          allEvents = await deps.listEvents(executionId)
        } catch (err) {
          return executionFailure('error', String(err))
        }

        const sliced = sliceAfterId(allEvents, baselineId)
        const turnEvents = sliced.events
        anchored = sliced.anchored

        if (!started) {
          const running = findStatusEvent(turnEvents, ['RUNNING'])
          if (running) {
            runningIndex = running.index
            workDeadline = now() + workTimeoutMs
            started = true
          } else if (now() > startDeadline) {
            await killQuietly(executionId)
            return executionFailure('start_timeout', `Le case n'est pas passé à RUNNING en ${startTimeoutMs}ms.`, {
              killedByBudget: true,
              anchored,
            })
          } else {
            continue
          }
        }

        // F7 — advance runningIndex to the most recent RUNNING. The work budget
        // is not reset per RUNNING: it covers the whole turn.
        const lastRunning = findLastStatusEvent(turnEvents, ['RUNNING'])
        if (lastRunning && lastRunning.index > runningIndex) {
          runningIndex = lastRunning.index
        }

        const quiescent = findStatusEvent(turnEvents, QUIESCENT_STATUSES, runningIndex + 1)
        if (quiescent) {
          return buildTurnResult(turnEvents, allEvents, quiescent.event, anchored)
        }

        if (started && now() > workDeadline) {
          await killQuietly(executionId)
          const toolResponses = turnEvents.filter((e) => e.type === 'ToolResponseEvent')
          return {
            ...executionFailure('work_timeout', `L'agent n'a pas atteint la quiescence en ${workTimeoutMs}ms.`),
            events: toRuntimeEvents(turnEvents),
            agentsSelected: collectAgentsSelected(turnEvents),
            agentTurns: countType(turnEvents, 'AgentFinishedEvent'),
            toolCallCount: toolResponses.length,
            failedToolCalls: buildFailedToolCalls(toolResponses),
            killedByBudget: true,
            anchored,
            llmModels: collectLlmModels(turnEvents),
          }
        }
      }
    },
  }
}

export { CASE_STATUS_EVENT }
