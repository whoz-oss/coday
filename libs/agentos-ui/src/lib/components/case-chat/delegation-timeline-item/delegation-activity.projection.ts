import {
  CaseEvent,
  MessageEvent as CaseMessageEvent,
  ToolRequestEvent,
  ToolResponseEvent,
} from '@whoz-oss/agentos-api-client'

export interface ChildActivity {
  id: string
  kind: 'intention' | 'tool' | 'message' | 'question' | 'error' | 'warn' | 'agent' | 'finished'
  label: string
  detail?: string
  count?: number
  failed?: boolean
}

/** Projects child events without exposing the parent delegation's injected user brief. */
export function projectDelegationActivity(events: CaseEvent[], options: { technical: boolean }): ChildActivity[] {
  const toolNames = new Map<string, string>()
  const detailed: ChildActivity[] = []
  const intentions = new Set<string>()

  for (const event of events) {
    if (event.type === 'ToolRequestEvent') {
      const tool = event as ToolRequestEvent
      const id = tool.toolRequestId ?? event.id
      toolNames.set(id, tool.toolName ?? 'Tool')
      if (options.technical)
        detailed.push({
          id: `${id}-request`,
          kind: 'tool',
          label: `${tool.toolName ?? 'Tool'} requested`,
          detail: tool.args ?? undefined,
        })
    } else if (event.type === 'ToolResponseEvent') {
      const tool = event as ToolResponseEvent
      const id = tool.toolRequestId ?? event.id
      const name = toolNames.get(id) ?? tool.toolName ?? 'Tool'
      const label = `${name} ${tool.success ? 'completed' : 'failed'}`
      detailed.push({
        id: `${id}-response`,
        kind: 'tool',
        label,
        detail: options.technical ? outputText(tool.output) : undefined,
        failed: !tool.success,
      })
    } else if (event.type === 'IntentionGeneratedEvent') {
      const intention = (event as CaseEvent & { intention?: string }).intention?.trim()
      if (intention && (options.technical || !intentions.has(intention))) {
        intentions.add(intention)
        detailed.push({ id: event.id, kind: 'intention', label: 'Plan', detail: intention })
      }
    } else if (event.type === 'QuestionEvent') {
      detailed.push({
        id: event.id,
        kind: 'question',
        label: 'Question',
        detail: (event as CaseEvent & { question?: string }).question,
      })
    } else if (event.type === 'ErrorEvent' || event.type === 'WarnEvent') {
      detailed.push({
        id: event.id,
        kind: event.type === 'ErrorEvent' ? 'error' : 'warn',
        label: event.type === 'ErrorEvent' ? 'Error' : 'Warning',
        detail: (event as CaseEvent & { message?: string }).message,
        failed: event.type === 'ErrorEvent',
      })
    } else if (options.technical && event.type === 'MessageEvent') {
      const message = event as CaseMessageEvent
      const text =
        message.content
          ?.filter((part): part is { content: string } => 'content' in part)
          .map((part) => part.content)
          .join('') ?? ''
      if (text)
        detailed.push({
          id: event.id,
          kind: 'message',
          label: message.actor.displayName || message.actor.role,
          detail: text,
        })
    } else if (options.technical && event.type === 'AgentRunningEvent') {
      detailed.push({ id: event.id, kind: 'agent', label: `${event.agentName || 'Agent'} working` })
    } else if (options.technical && event.type === 'AgentFinishedEvent') {
      detailed.push({ id: event.id, kind: 'finished', label: `${event.agentName || 'Agent'} finished` })
    }
  }

  if (options.technical) return detailed
  const tools = new Map<string, ChildActivity>()
  for (const tool of detailed.filter((item) => item.kind === 'tool')) {
    // Tool response labels are constructed above as "<name> completed|failed".
    // String#replace always returns a string, unlike array indexing under
    // noUncheckedIndexedAccess.
    const name = tool.label.replace(/ (completed|failed)$/, '') || 'Tool'
    const key = `${tool.failed ? 'failed' : 'completed'}:${name}`
    const existing = tools.get(key)
    if (existing) existing.count = (existing.count ?? 1) + 1
    else tools.set(key, { ...tool, id: `tool-${key}`, label: name, count: 1 })
  }
  // A compact normal view prioritizes current plans and attention-worthy events;
  // tool entries are represented once per name/outcome rather than per request/response.
  return [...detailed.filter((item) => item.kind !== 'tool'), ...tools.values()]
}

function outputText(output: unknown): string | undefined {
  return output && typeof output === 'object' && typeof (output as { content?: unknown }).content === 'string'
    ? (output as { content: string }).content
    : undefined
}
