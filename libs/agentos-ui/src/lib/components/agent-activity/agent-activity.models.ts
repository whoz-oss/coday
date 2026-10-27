import {
  CaseEvent,
  PendingConfirmationEvent,
  SubCaseFinishedEvent,
  SubCaseStartedEvent,
} from '@whoz-oss/agentos-api-client'

/**
 * One-line summary of what an agent is doing right now, shown in place of a spinner.
 *
 * The sentence doubles as the identity of the step: the activity line replays its entrance
 * animation and restarts its elapsed counter when `label` changes, so a sentence that merely
 * gains precision (a tool call learning its arguments) stays visually stable.
 */
export interface AgentActivity {
  /** The sentence itself, e.g. `Reading case-chat.component.ts`. */
  label: string
  /** Qualified tool name behind the sentence, surfaced as the line's tooltip. */
  detail?: string
}

/** What the line says while the agent holds the turn but no event tells more than that. */
const THINKING: AgentActivity = { label: 'Thinking' }

/** Sentences for one tool, picked depending on whether an argument names the target. */
interface ToolPhrase {
  /** Used when no argument is found, e.g. `Reading a file`. */
  plain: string
  /** Used when one is, `{}` standing for its value, e.g. `Reading {}`. */
  detailed?: string
  /** Argument keys whose value fills `{}`, in priority order. */
  args?: readonly string[]
}

/**
 * The tool catalogue, keyed by the action part of a qualified tool name (`PREFIX__action`,
 * see the SDK's ToolPlugin) reduced to its letters and digits. Tools absent from this table —
 * bash tools named by configuration, MCP tools, anything a plugin adds — are phrased by
 * `genericSentence` from their own name.
 */
const TOOL_PHRASES: Record<string, ToolPhrase> = {
  answer: { plain: 'Writing the answer' },
  delegate: { plain: 'Delegating a task', detailed: 'Delegating to {}', args: ['agentName'] },
  editfiles: { plain: 'Editing files', detailed: 'Editing {}', args: ['path', 'filePaths'] },
  getcurrentdatetime: { plain: 'Checking the date and time' },
  gitcommit: { plain: 'Committing', detailed: 'Committing {}', args: ['message'] },
  gitcreatebranch: { plain: 'Creating a branch', detailed: 'Creating the branch {}', args: ['name'] },
  gitcreatepullrequest: { plain: 'Opening a pull request', detailed: 'Opening the pull request {}', args: ['title'] },
  gitfetch: { plain: 'Fetching from the remote' },
  gitpush: { plain: 'Pushing the branch' },
  gitstatus: { plain: 'Checking the Git status' },
  listfiles: { plain: 'Listing files', detailed: 'Listing files in {}', args: ['relPath'] },
  movefile: { plain: 'Moving a file', detailed: 'Moving {}', args: ['from'] },
  readasimage: { plain: 'Reading an image', detailed: 'Reading {}', args: ['filePath'] },
  readdocument: { plain: 'Reading a document', detailed: 'Reading {}', args: ['filePath'] },
  readfile: { plain: 'Reading a file', detailed: 'Reading {}', args: ['filePath'] },
  readskill: { plain: 'Reading a skill', detailed: 'Reading the skill {}', args: ['name'] },
  readskillresource: { plain: 'Reading a skill resource', detailed: 'Reading {}', args: ['path', 'name'] },
  readspreadsheet: { plain: 'Reading a spreadsheet', detailed: 'Reading {}', args: ['filePath'] },
  redirect: { plain: 'Handing over', detailed: 'Handing over to {}', args: ['agentName'] },
  remove: { plain: 'Removing a file', detailed: 'Removing {}', args: ['path'] },
  searchfiles: { plain: 'Searching files', detailed: 'Searching files for {}', args: ['fileName', 'fileContent'] },
}

/** Gerund of the verbs that open an action name, e.g. `createIssue` → `Creating issue`. */
const GERUNDS: Record<string, string> = {
  add: 'Adding',
  ask: 'Asking',
  build: 'Building',
  call: 'Calling',
  check: 'Checking',
  close: 'Closing',
  commit: 'Committing',
  convert: 'Converting',
  count: 'Counting',
  create: 'Creating',
  delete: 'Deleting',
  deploy: 'Deploying',
  download: 'Downloading',
  edit: 'Editing',
  execute: 'Running',
  fetch: 'Fetching',
  find: 'Finding',
  generate: 'Generating',
  get: 'Getting',
  invoke: 'Calling',
  list: 'Listing',
  load: 'Loading',
  move: 'Moving',
  open: 'Opening',
  patch: 'Patching',
  post: 'Posting',
  pull: 'Pulling',
  push: 'Pushing',
  query: 'Querying',
  read: 'Reading',
  remove: 'Removing',
  rename: 'Renaming',
  run: 'Running',
  search: 'Searching',
  send: 'Sending',
  set: 'Setting',
  start: 'Starting',
  stop: 'Stopping',
  translate: 'Translating',
  update: 'Updating',
  upload: 'Uploading',
  write: 'Writing',
}

/** Argument keys that most often carry the object of a tool outside the catalogue. */
const GENERIC_ARGS = ['command', 'query', 'path', 'filePath', 'url', 'name', 'title', 'message', 'task'] as const

/** Longest argument value the line shows; beyond it the value is elided. */
const MAX_VALUE_LENGTH = 48

/** How deep an argument tree is searched — `delegations[0].agentName` sits at depth 3. */
const MAX_ARG_DEPTH = 4

/** How many values of one argument key are counted before the search stops. */
const MAX_ARG_VALUES = 20

/**
 * The sentence for a tool call: the catalogue entry when there is one, a sentence built from
 * the tool's own name otherwise. `args` is the raw payload of the event — JSON for a
 * structured call, free text for the rest, and absent before the arguments are generated.
 */
export function toolActivityLabel(toolName: string, args?: string | null): string {
  const separator = toolName.lastIndexOf('__')
  const action = separator >= 0 ? toolName.slice(separator + 2) : toolName
  const parsed = parseArgs(args)
  const phrase = TOOL_PHRASES[action.toLowerCase().replace(/[^a-z0-9]/g, '')]

  if (phrase) {
    const value = phrase.args ? findArgument(parsed, phrase.args) : null
    return value && phrase.detailed ? phrase.detailed.replace('{}', value) : phrase.plain
  }

  const sentence = genericSentence(action)
  const value = findArgument(parsed, GENERIC_ARGS)
  return value ? `${sentence}: ${value}` : sentence
}

/**
 * The step the agent is on, read from the event stream of the case.
 *
 * Walks the stream backwards and stops at the first event that names a step: statuses,
 * warnings and end-of-turn markers say nothing about the present and are walked through.
 * Always returns a sentence — the caller shows the line only while the turn is running,
 * and a turn that has just started has nothing more to say than "Thinking".
 */
export function describeAgentActivity(events: readonly CaseEvent[]): AgentActivity {
  for (let index = events.length - 1; index >= 0; index--) {
    const event = events[index]
    if (!event) continue

    switch (event.type) {
      case 'TextChunkEvent':
        return { label: 'Writing the answer' }
      case 'QuestionEvent':
        return { label: 'Waiting for your answer' }
      case 'PendingConfirmationEvent':
        return { label: 'Waiting for your confirmation', detail: event.toolName }
      case 'ConfirmationResolvedEvent': {
        // The tool runs as soon as the confirmation is resolved, and only the pending event
        // carries its name and arguments.
        const pending = events.find(
          (candidate): candidate is PendingConfirmationEvent =>
            candidate.type === 'PendingConfirmationEvent' && candidate.id === event.pendingEventId
        )
        return pending ? toolActivity(pending.toolName, pending.inputJson) : THINKING
      }
      case 'ToolRequestEvent':
        return toolActivity(event.toolName, event.args)
      case 'ToolSelectedEvent':
      case 'IntentionGeneratedEvent':
        // The tool is known, its arguments are not yet: the same sentence, less precise.
        return toolActivity(event.toolName)
      case 'ToolResponseEvent':
        return { label: 'Planning the next step' }
      case 'SubCaseStartedEvent': {
        const running = runningDelegations(events)
        // Every sub-case is over: the delegating tool call below tells the rest of the story.
        if (running.length === 0) continue
        return { label: delegationLabel(running) }
      }
      case 'AgentRunningEvent':
      case 'AgentSelectedEvent':
      case 'AnswerEvent':
      case 'MessageEvent':
        return THINKING
      default:
        continue
    }
  }

  return THINKING
}

function toolActivity(toolName: string, args?: string | null): AgentActivity {
  return { label: toolActivityLabel(toolName, args), detail: toolName }
}

/** The sub-cases started and not yet finished, in start order. */
function runningDelegations(events: readonly CaseEvent[]): SubCaseStartedEvent[] {
  const finished = new Set(
    events
      .filter((event): event is SubCaseFinishedEvent => event.type === 'SubCaseFinishedEvent')
      .map((event) => event.delegationId)
  )
  return events.filter(
    (event): event is SubCaseStartedEvent => event.type === 'SubCaseStartedEvent' && !finished.has(event.delegationId)
  )
}

/** Names the delegate while there is one to name, counts them beyond that. */
function delegationLabel(running: readonly SubCaseStartedEvent[]): string {
  const names = [...new Set(running.map((event) => event.agentName).filter((name) => name.trim().length > 0))]
  if (names.length === 1) return `Delegating to ${names[0]}`
  if (names.length === 0) return `Delegating ${running.length} tasks`
  return `Delegating to ${names.length} agents`
}

/**
 * A sentence for a tool the catalogue does not know, built from its action name:
 * `createIssue` → `Creating issue`, `bash` → `Running bash`.
 */
function genericSentence(action: string): string {
  const words = action
    .replace(/[^A-Za-z0-9]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter((word) => word.length > 0)

  if (words.length === 0) return 'Running a tool'

  const [first, ...rest] = words
  const gerund = first ? GERUNDS[first] : undefined
  if (!gerund) return `Running ${words.join(' ')}`
  return rest.length > 0 ? `${gerund} ${rest.join(' ')}` : gerund
}

/** Tool arguments as a tree; tools also send free text, which names no target. */
function parseArgs(args?: string | null): unknown {
  if (!args) return null
  try {
    return JSON.parse(args)
  } catch {
    return null
  }
}

/**
 * The value of the first key that appears in the argument tree, shortened for a single line
 * and followed by `+n` when the same key holds several values (several edited files, several
 * delegations). Keys are tried in order, so the one that best describes the tool wins over
 * the one that merely sits higher in the tree.
 */
function findArgument(args: unknown, keys: readonly string[]): string | null {
  for (const key of keys) {
    const values = collectValues(args, key, 0)
    const first = values[0]
    if (first === undefined) continue
    return values.length > 1 ? `${shorten(first)} +${values.length - 1}` : shorten(first)
  }
  return null
}

/** Every string stored under `key` in the tree, down to `MAX_ARG_DEPTH`. */
function collectValues(node: unknown, key: string, depth: number): string[] {
  if (depth > MAX_ARG_DEPTH || node === null || typeof node !== 'object') return []

  const values: string[] = []

  const push = (candidate: unknown): void => {
    if (typeof candidate === 'string' && candidate.trim().length > 0) values.push(candidate)
  }

  if (Array.isArray(node)) {
    for (const item of node) {
      values.push(...collectValues(item, key, depth + 1))
      if (values.length >= MAX_ARG_VALUES) return values.slice(0, MAX_ARG_VALUES)
    }
    return values
  }

  const record = node as Record<string, unknown>
  const own = record[key]
  push(own)
  if (Array.isArray(own)) own.forEach(push)

  for (const [name, value] of Object.entries(record)) {
    if (name === key) continue
    values.push(...collectValues(value, key, depth + 1))
    if (values.length >= MAX_ARG_VALUES) return values.slice(0, MAX_ARG_VALUES)
  }

  return values
}

/** Keeps a value on one line: the tail of a path, the head of a sentence. */
function shorten(value: string): string {
  const single = value.replace(/\s+/g, ' ').trim()
  if (single.length <= MAX_VALUE_LENGTH) return single
  if (single.includes('/')) return `…${single.slice(single.length - MAX_VALUE_LENGTH + 1)}`
  return `${single.slice(0, MAX_VALUE_LENGTH - 1)}…`
}
