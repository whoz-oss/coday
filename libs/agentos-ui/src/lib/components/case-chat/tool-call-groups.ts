import { toolActivityLabel } from '../agent-activity/agent-activity.models'
import { TimelineItem, ToolCall } from './case-chat.models'

/** How many distinct step labels the collapsed summary of a finished group lists. */
const DETAIL_LABEL_COUNT = 3

export interface ToolGroupSummary {
  total: number
  pending: number
  failed: number
  succeeded: number
  /** Headline: the live step while a call is pending, `Used n tools` once all are done. */
  label: string
  /** Muted second line of a finished group: the first distinct steps, `+n more` beyond. Empty while running. */
  detail: string
}

/**
 * Collapses every run of two or more consecutive tool items into one `toolGroup` item.
 *
 * A lone tool call stays as it is, and any other item (message, delegation, notice...) ends a run. The group id
 * derives from its first call, so it stays the same as the run grows and the expanded state survives live updates.
 */
export function groupToolCalls(items: TimelineItem[]): TimelineItem[] {
  const result: TimelineItem[] = []
  let run: ToolCall[] = []

  const flush = (): void => {
    const [first] = run
    if (first && run.length === 1) {
      result.push({ kind: 'tool', call: first })
    } else if (first) {
      result.push({ kind: 'toolGroup', groupId: `group-${first.requestId}`, calls: run })
    }
    run = []
  }

  for (const item of items) {
    if (item.kind === 'tool') {
      run.push(item.call)
    } else {
      flush()
      result.push(item)
    }
  }
  flush()

  return result
}

export function summarizeToolGroup(calls: ToolCall[]): ToolGroupSummary {
  const total = calls.length
  const pendingCalls = calls.filter((call) => !call.response)
  const pending = pendingCalls.length
  const failed = calls.filter((call) => call.response && !call.response.success).length
  const succeeded = total - pending - failed

  const liveStep = pendingCalls[pendingCalls.length - 1]
  if (liveStep) {
    return {
      total,
      pending,
      failed,
      succeeded,
      label: toolActivityLabel(liveStep.toolName, liveStep.args),
      detail: '',
    }
  }

  const labels = [...new Set(calls.map((call) => toolActivityLabel(call.toolName, call.args)))]
  const shown = labels.slice(0, DETAIL_LABEL_COUNT).join(' · ')
  const more = labels.length - DETAIL_LABEL_COUNT
  return {
    total,
    pending,
    failed,
    succeeded,
    label: `Used ${total} ${total === 1 ? 'tool' : 'tools'}`,
    detail: more > 0 ? `${shown} · +${more} more` : shown,
  }
}
