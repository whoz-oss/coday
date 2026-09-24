/**
 * Read-only deterministic reconnaissance for the Forge Run Protocol.
 *
 * Usage: npx ts-node coday/scripts/forge-run-recon.ts --input <raw-input>
 *   --jira-result <normalized-json> [--project-root <repository-root>]
 */
import * as fs from 'fs'
import * as path from 'path'
import {
  discoverProjectRoot,
  readWorkstreamRegistry,
  resolveWorkstreamContext,
  type WorkstreamContext,
} from './bmad-workstream-registry'

const TICKET = /^WZ-[1-9][0-9]*$/
const ALLOWED_JIRA_KEYS = new Set(['status', 'ticket', 'reason', 'gate_markers'])
const GATE_MARKERS = ['gate_1', 'gate_2', 'gate_3', 'gate_4'] as const
const HANDOFF_GATES = new Set<string>(GATE_MARKERS)
type GateMarker = (typeof GATE_MARKERS)[number]

export type JiraResult =
  | { status: 'found'; ticket: { key: string; summary: string; epicKey?: string }; gate_markers: GateMarker[] }
  | { status: 'missing' }
  | { status: 'unavailable'; reason: string }
  | { status: 'not_requested' }

export type ForgeRunRecon = {
  ok: true
  input: { raw: string; ticket: string | null; intent: string | null }
  jira: JiraResult & { confirmed: boolean }
  evidence: {
    handoff: { state: 'absent' | 'invalid' | 'valid'; next_action: string | null }
    run: { state: 'absent' | 'invalid' | 'present'; completed: boolean; last_completed_gate: string | null }
    bmad: { workstream: string | null; allowed_paths: string[] }
  }
  warnings: string[]
  mode: 'Waiting' | 'Resume' | 'Reconstruct' | 'BMad-partial' | 'Fresh-start'
  proposed_action: {
    kind: string
    gate: 1 | 2 | 3 | 4 | null
    requires_confirmation: true
    /**
     * Jira epic key for this ticket (e.g. "WZ-34386").
     * Present only when Jira status is 'found' and the ticket belongs to an epic.
     * Use this as `epic.id` when calling forge-factory-launch.
     * When absent, use the ticket key itself as both `epic.id` and `stories[0].id`.
     */
    epicKey?: string
  }
  summary: string
}

type ParsedHandoff = { workstream: string; nextAction: string; lastCompletedGate: string | null; artifacts: string[] }
type ScopedEvidence = { context: WorkstreamContext; artifacts: string[] }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function closedKeys(value: Record<string, unknown>, allowed: Set<string>, label: string): void {
  for (const key of Object.keys(value))
    if (!allowed.has(key)) throw new Error(`${label} contains unsupported field: ${key}`)
}

function parseGateMarkers(value: unknown): GateMarker[] {
  if (value === undefined) return []
  if (
    !Array.isArray(value) ||
    value.some((marker) => typeof marker !== 'string' || !GATE_MARKERS.includes(marker as GateMarker))
  )
    throw new Error('Jira gate_markers must contain only gate_1 through gate_4')
  const unique = [...new Set(value as GateMarker[])]
  if (
    unique.length !== value.length ||
    unique.some((marker, index) => index > 0 && GATE_MARKERS.indexOf(marker) <= GATE_MARKERS.indexOf(unique[index - 1]))
  )
    throw new Error('Jira gate_markers must be unique and ordered')
  return unique
}

export function parseJiraResult(value: unknown): JiraResult {
  if (!isRecord(value)) throw new Error('Jira result must be an object')
  closedKeys(value, ALLOWED_JIRA_KEYS, 'Jira result')
  if (value.status === 'not_requested' && Object.keys(value).length === 1) return { status: 'not_requested' }
  if (value.status === 'missing' && Object.keys(value).length === 1) return { status: 'missing' }
  if (
    value.status === 'unavailable' &&
    typeof value.reason === 'string' &&
    value.reason.trim() &&
    Object.keys(value).length === 2
  )
    return { status: 'unavailable', reason: value.reason }
  if (
    value.status === 'found' &&
    isRecord(value.ticket) &&
    (Object.keys(value).length === 2 || Object.keys(value).length === 3)
  ) {
    closedKeys(value.ticket, new Set(['key', 'summary', 'epicKey']), 'Jira ticket')
    if (
      typeof value.ticket.key === 'string' &&
      TICKET.test(value.ticket.key) &&
      typeof value.ticket.summary === 'string'
    ) {
      if (
        value.ticket.epicKey !== undefined &&
        (typeof value.ticket.epicKey !== 'string' || !TICKET.test(value.ticket.epicKey))
      )
        throw new Error('Jira ticket epicKey must be a valid Jira ticket ID')
      const epicKey = value.ticket.epicKey as string | undefined
      return {
        status: 'found',
        ticket: { key: value.ticket.key, summary: value.ticket.summary, ...(epicKey ? { epicKey } : {}) },
        gate_markers: parseGateMarkers(value.gate_markers),
      }
    }
  }
  throw new Error('Jira result does not match the closed normalized schema')
}

export function classifyInput(raw: string): { raw: string; ticket: string | null; intent: string | null } {
  const trimmed = raw.trim()
  if (!trimmed || trimmed.length > 5000) throw new Error('input must contain at most 5000 non-empty characters')
  const positional = trimmed.replace(/^\/forge-run(?:\s+|$)/i, '').trim()
  if (!positional) throw new Error('input must contain a ticket or product intent')
  const first = /^(\S+)(?:\s+([\s\S]*))?$/.exec(positional)
  const firstToken = first?.[1] ?? ''
  if (TICKET.test(firstToken)) {
    const intent = first?.[2]?.trim() || null
    if (intent?.split(/\s+/).some((token) => TICKET.test(token))) throw new Error('multiple tickets are not supported')
    return { raw, ticket: firstToken, intent }
  }
  if (positional.split(/\s+/).some((token) => TICKET.test(token)))
    throw new Error('ticket input must begin with one WZ ticket identifier')
  return { raw, ticket: null, intent: positional }
}

function frontmatter(content: string): Record<string, string | string[]> | null {
  const match = /^---\n([\s\S]*?)\n---/m.exec(content)
  if (!match) return null
  const result: Record<string, string | string[]> = {}
  const lines = match[1].split('\n')
  for (let index = 0; index < lines.length; index++) {
    const entry = /^([a-z_]+):\s*(.*)$/.exec(lines[index])
    if (!entry) continue
    if (entry[2]) result[entry[1]] = entry[2].replace(/^"|"$/g, '')
    else {
      const values: string[] = []
      while (/^\s+-\s+/.test(lines[index + 1] ?? ''))
        values.push((lines[++index].match(/^\s+-\s+(.*)$/) ?? ['', ''])[1])
      result[entry[1]] = values
    }
  }
  return result
}

function readFile(file: string): string | null {
  try {
    return fs.existsSync(file) ? fs.readFileSync(file, 'utf-8') : null
  } catch {
    return null
  }
}

function readStateFile(file: string, label: string, warnings: string[]): string | null {
  if (!fs.existsSync(file)) return null
  try {
    return fs.readFileSync(file, 'utf-8')
  } catch {
    warnings.push(`ignored unreadable ${label} evidence`)
    return null
  }
}

function isContained(candidate: string, root: string): boolean {
  try {
    const relative = path.relative(fs.realpathSync(root), fs.realpathSync(candidate))
    return relative !== '' && !relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative)
  } catch {
    return false
  }
}

function isLexicallyContained(candidate: string, root: string): boolean {
  const relative = path.relative(path.resolve(root), path.resolve(candidate))
  return relative !== '' && !relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative)
}

function parseHandoff(content: string, ticket: string): ParsedHandoff | null {
  const values = frontmatter(content)
  if (
    !values ||
    values.ticket_id !== ticket ||
    typeof values.workstream !== 'string' ||
    !values.workstream ||
    typeof values.next_action !== 'string' ||
    !values.next_action
  )
    return null
  const lastCompletedGate = typeof values.last_completed_gate === 'string' ? values.last_completed_gate : null
  if (lastCompletedGate && !HANDOFF_GATES.has(lastCompletedGate)) return null
  return {
    workstream: values.workstream,
    nextAction: values.next_action,
    lastCompletedGate,
    artifacts: Array.isArray(values.artifact_paths) ? values.artifact_paths : [],
  }
}

function gateFrom(lastCompletedGate: string | null): 1 | 2 | 3 | 4 {
  if (lastCompletedGate === 'gate_1') return 2
  if (lastCompletedGate === 'gate_2') return 3
  if (lastCompletedGate === 'gate_3') return 4
  return 1
}

function safeContext(
  projectRoot: string,
  workstream: string,
  artifacts: string[],
  warnings: string[]
): ScopedEvidence | null {
  try {
    const context = resolveWorkstreamContext(projectRoot, workstream)
    const safeArtifacts: string[] = []
    for (const artifact of artifacts) {
      const absolute = path.resolve(projectRoot, artifact)
      if (!fs.existsSync(absolute)) {
        if (!isLexicallyContained(absolute, context.workstream_root))
          warnings.push(`dropped unsafe BMad artifact path: ${artifact}`)
        else warnings.push(`dropped missing BMad artifact path: ${artifact}`)
      } else if (!isContained(absolute, context.workstream_root))
        warnings.push(`dropped unsafe BMad artifact path: ${artifact}`)
      else safeArtifacts.push(absolute)
    }
    return { context, artifacts: [...new Set(safeArtifacts)].sort() }
  } catch (error) {
    warnings.push(`BMad workstream rejected: ${(error as Error).message}`)
    return null
  }
}

function ownsTicketArtifact(candidate: string, ticket: string, storyDirectory: boolean, warnings: string[]): boolean {
  if (!candidate.endsWith('.md')) return false
  const content = readFile(candidate)
  if (content === null) {
    warnings.push(`ignored unreadable BMad artifact: ${candidate}`)
    return false
  }
  const values = frontmatter(content)
  if (!values) return false
  const ownershipKeys = ['ticket', 'jira', 'id', 'epic'] as const
  const duplicateOwnershipKey = ownershipKeys.find(
    (key) => (content.match(new RegExp(`^${key}:`, 'gm')) ?? []).length > 1
  )
  if (duplicateOwnershipKey) {
    warnings.push(`ignored duplicate ticket metadata: ${candidate}`)
    return false
  }
  const primaryKeys = ['ticket', 'jira'] as const
  const primary = primaryKeys.filter((key) => values[key] !== undefined)
  if (primary.length > 0) {
    if (primary.some((key) => typeof values[key] !== 'string' || values[key] !== ticket)) {
      warnings.push(`ignored contradictory ticket metadata: ${candidate}`)
      return false
    }
    const secondaryKeys = ['id', 'epic'] as const
    if (
      secondaryKeys.some(
        (key) =>
          values[key] !== undefined &&
          (typeof values[key] !== 'string' ||
            // `jira`/`ticket` own the external Jira identifier. BMad's `id`
            // and `epic` may instead be internal identifiers (for example
            // TP-S-012 and TP-E-005), so only a second WZ reference must
            // agree with the owning Jira ticket.
            (values[key].startsWith('WZ-') &&
              (key === 'id' ? !values[key].startsWith(`${ticket}-`) : values[key] !== ticket)))
      )
    ) {
      warnings.push(`ignored contradictory ticket metadata: ${candidate}`)
      return false
    }
    return true
  }
  if (!storyDirectory) return false
  const secondaryKeys = ['id', 'epic'] as const
  const secondary = secondaryKeys.filter((key) => values[key] !== undefined)
  if (
    secondary.length === 0 ||
    secondary.some(
      (key) =>
        typeof values[key] !== 'string' ||
        (key === 'id' ? !values[key].startsWith(`${ticket}-`) : values[key] !== ticket)
    )
  ) {
    if (secondary.length > 0) warnings.push(`ignored contradictory ticket metadata: ${candidate}`)
    return false
  }
  return true
}

function findTicketArtifacts(
  directory: string,
  ticket: string,
  storyDirectory: boolean,
  workstreamRoot: string,
  warnings: string[]
): string[] {
  if (!fs.existsSync(directory) || !isContained(directory, workstreamRoot)) return []
  try {
    if (fs.lstatSync(directory).isSymbolicLink()) return []
  } catch {
    return []
  }
  const artifacts: string[] = []
  for (const entry of fs.readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const candidate = path.join(directory, entry.name)
    if (entry.isSymbolicLink()) continue
    if (!isContained(candidate, workstreamRoot)) continue
    if (entry.isDirectory())
      artifacts.push(...findTicketArtifacts(candidate, ticket, storyDirectory, workstreamRoot, warnings))
    if (entry.isFile() && ownsTicketArtifact(candidate, ticket, storyDirectory, warnings)) artifacts.push(candidate)
  }
  return artifacts
}

function discoverBmadContext(projectRoot: string, ticket: string, warnings: string[]): ScopedEvidence[] {
  let slugs: string[]
  try {
    slugs = [...readWorkstreamRegistry(projectRoot).keys()].sort()
  } catch (error) {
    warnings.push(`BMad registry rejected: ${(error as Error).message}`)
    return []
  }
  const matches: ScopedEvidence[] = []
  for (const slug of slugs) {
    try {
      const context = resolveWorkstreamContext(projectRoot, slug)
      const nestedEpics = path.join(context.workstream_root, 'implementation-artifacts', 'epics')
      const artifacts = [
        ...findTicketArtifacts(context.artifact_workspaces.stories, ticket, true, context.workstream_root, warnings),
        ...findTicketArtifacts(nestedEpics, ticket, true, context.workstream_root, warnings),
        ...findTicketArtifacts(
          path.join(context.workstream_root, 'planning-artifacts'),
          ticket,
          false,
          context.workstream_root,
          warnings
        ),
      ]
      const uniqueArtifacts = [...new Set(artifacts)].sort()
      for (const artifact of uniqueArtifacts)
        if (isContained(artifact, nestedEpics)) warnings.push(`noncanonical nested BMad artifact layout: ${artifact}`)
      if (uniqueArtifacts.length > 0) matches.push({ context, artifacts: uniqueArtifacts })
    } catch (error) {
      warnings.push(`BMad workstream rejected: ${(error as Error).message}`)
    }
  }
  if (matches.length > 1) warnings.push(`multiple BMad workstreams contain ${ticket}; confirmation required`)
  return matches
}

function producerRunEvidence(content: string | null): {
  lastCompletedGate: string | null
  completed: boolean
  records: number
} {
  if (!content) return { lastCompletedGate: null, completed: false, records: 0 }
  const block = /^# Gate ([1-4]) closed at [^\n]+\n((?:(?!# Gate [1-4] closed at )#[^\n]*\n?)*)/gm
  let match: RegExpExecArray | null
  let latest: string | null = null
  let completed = false
  let records = 0
  while ((match = block.exec(content))) {
    const gate = match[1]
    const details = match[2]
    const decision = new RegExp(
      `^# human_decision: ${gate === '4' ? 'approved' : 'approved(?:-with-changes)?'}\\s*$`,
      'm'
    )
    const producerMetadata =
      gate === '1' || gate === '3'
        ? /^# verdict: .+$/m.test(details)
        : gate === '2'
          ? /^# branch: .+$/m.test(details)
          : /^# run_outcome: completed\s*$/m.test(details)
    if (!decision.test(details) || !producerMetadata) continue
    records += 1
    latest = `gate_${gate}`
    completed = gate === '4'
  }
  return { lastCompletedGate: latest, completed, records }
}

export function forgeRunRecon(options: { projectRoot?: string; input: string; jira: unknown }): ForgeRunRecon {
  const input = classifyInput(options.input)
  const jira = parseJiraResult(options.jira)
  if (!input.ticket && jira.status !== 'not_requested')
    throw new Error('free-text intent requires Jira status not_requested')
  if (input.ticket && jira.status === 'not_requested')
    throw new Error('ticket input requires a normalized Jira read result')
  if (jira.status === 'missing') throw new Error(`Jira ticket missing: ${input.ticket}`)
  if (jira.status === 'found' && jira.ticket.key !== input.ticket)
    throw new Error('Jira result ticket does not match input ticket')
  const projectRoot = path.resolve(options.projectRoot ?? discoverProjectRoot())
  const warnings: string[] = []
  if (jira.status === 'unavailable') warnings.push(`Jira unavailable: ${jira.reason}`)
  const handoffContent = input.ticket
    ? readStateFile(path.join(projectRoot, 'forge/state/handoffs', `${input.ticket}.md`), 'handoff', warnings)
    : null
  const parsedHandoff = handoffContent && input.ticket ? parseHandoff(handoffContent, input.ticket) : null
  const handoffState = !handoffContent ? 'absent' : parsedHandoff ? 'valid' : 'invalid'
  if (handoffContent && !parsedHandoff) warnings.push('ignored corrupt handoff evidence')
  const runContent = input.ticket
    ? readStateFile(path.join(projectRoot, 'forge/state/forge-runs', `${input.ticket}.yaml`), 'run', warnings)
    : null
  const runEvidence = producerRunEvidence(runContent)
  const runIsRecognizable = runEvidence.records > 0
  const completed = runEvidence.completed
  const jiraLastGate = jira.status === 'found' ? (jira.gate_markers[jira.gate_markers.length - 1] ?? null) : null
  const runLastGate = parsedHandoff?.lastCompletedGate ?? jiraLastGate ?? runEvidence.lastCompletedGate
  const runState = !runContent ? 'absent' : runIsRecognizable ? 'present' : 'invalid'
  if (runContent && !runIsRecognizable) warnings.push('ignored malformed run evidence')
  const handoffContext = parsedHandoff
    ? safeContext(projectRoot, parsedHandoff.workstream, parsedHandoff.artifacts, warnings)
    : null
  const handoffUsable = Boolean(parsedHandoff && handoffContext)
  if (parsedHandoff && !handoffContext) warnings.push('ignored unregistered handoff evidence')
  const discoveredContexts =
    !handoffUsable && input.ticket ? discoverBmadContext(projectRoot, input.ticket, warnings) : []
  const context = handoffContext ?? (discoveredContexts.length === 1 ? discoveredContexts[0] : null)
  const allowedPaths = context?.artifacts ?? []
  // epicKey: present when Jira knows the parent epic — authoritative input for forge-factory-launch.
  // ProductEngineer must pass this as `epic.id`; the ticket itself goes as `stories[0].id`.
  const epicKey = jira.status === 'found' ? jira.ticket.epicKey : undefined

  let mode: ForgeRunRecon['mode']
  let action: ForgeRunRecon['proposed_action']
  if (jira.status === 'unavailable') {
    mode = 'Waiting'
    action = { kind: 'await_jira_confirmation', gate: null, requires_confirmation: true }
  } else if (handoffUsable && parsedHandoff && !completed) {
    mode = 'Resume'
    action = {
      kind: 'resume_handoff',
      gate: gateFrom(parsedHandoff.lastCompletedGate),
      requires_confirmation: true,
      ...(epicKey ? { epicKey } : {}),
    }
  } else if (jiraLastGate && !completed) {
    mode = 'Reconstruct'
    action = {
      kind: 'reconstruct_from_jira_markers',
      gate: gateFrom(jiraLastGate),
      requires_confirmation: true,
      ...(epicKey ? { epicKey } : {}),
    }
  } else if (runIsRecognizable && !completed) {
    mode = 'Reconstruct'
    action = {
      kind: 'reconstruct_handoff',
      gate: gateFrom(runLastGate),
      requires_confirmation: true,
      ...(epicKey ? { epicKey } : {}),
    }
  } else if (discoveredContexts.length > 1) {
    mode = 'BMad-partial'
    action = {
      kind: 'select_bmad_workstream',
      gate: null,
      requires_confirmation: true,
      ...(epicKey ? { epicKey } : {}),
    }
  } else if (context) {
    mode = 'BMad-partial'
    action = { kind: 'propose_gate_1', gate: 1, requires_confirmation: true, ...(epicKey ? { epicKey } : {}) }
  } else {
    mode = 'Fresh-start'
    action = { kind: 'propose_gate_1', gate: 1, requires_confirmation: true, ...(epicKey ? { epicKey } : {}) }
  }
  if (completed) warnings.push('completed Gate 4 evidence starts a fresh run')
  const summary =
    jira.status === 'unavailable'
      ? `${input.ticket}: Jira is unavailable; waiting for a corrected ticket, retry, or new instructions; no gate is proposed.`
      : `${input.ticket ?? 'Intent'}: ${mode}; ${action.gate ? `propose Gate ${action.gate}` : 'Jira confirmation required'}; no action runs without human confirmation.`
  return {
    ok: true,
    input,
    jira: { ...jira, confirmed: jira.status === 'found' },
    evidence: {
      handoff: { state: handoffState, next_action: parsedHandoff?.nextAction ?? null },
      run: { state: runState, completed, last_completed_gate: runLastGate },
      bmad: { workstream: context?.context.workstream.slug ?? null, allowed_paths: allowedPaths },
    },
    warnings: [...new Set(warnings)].sort(),
    mode,
    proposed_action: action,
    summary,
  }
}

export function runForgeRunReconCli(args: string[], projectRoot?: string): number {
  try {
    const values = new Map<string, string>()
    const known = new Set(['--input', '--jira-result', '--project-root'])
    for (let index = 0; index < args.length; index += 2) {
      const flag = args[index]
      const value = args[index + 1]
      if (!known.has(flag)) throw new Error(`unknown argument: ${flag}`)
      if (!value || value.startsWith('--')) throw new Error(`missing value for ${flag}`)
      if (values.has(flag)) throw new Error(`duplicate argument: ${flag}`)
      values.set(flag, value)
    }
    const input = values.get('--input')
    const jiraRaw = values.get('--jira-result')
    if (!input || !jiraRaw) throw new Error('required arguments: --input and --jira-result')
    process.stdout.write(
      JSON.stringify(
        forgeRunRecon({ projectRoot: projectRoot ?? values.get('--project-root'), input, jira: JSON.parse(jiraRaw) })
      ) + '\n'
    )
    return 0
  } catch (error) {
    process.stderr.write(`error: ${(error as Error).message}\n`)
    return 1
  }
}

if (require.main === module) process.exitCode = runForgeRunReconCli(process.argv.slice(2))
