/**
 * Factory Cockpit — step fact classification & escaped rendering.
 *
 * Vanilla ESM, zero dependencies, zero build step.
 *
 * Facts are grouped by nature rather than dumped into a flat grid: an `exitCode`
 * and an `anchored` do not carry the same weight. The first IS the verdict, the
 * second is a reliability note about the measurement. Mixing them loses the
 * hierarchy.
 *
 * Every unknown fact falls into "Autres" — the default is VISIBILITY, never
 * silence. A workflow may add a fact we never anticipated and it still shows up.
 *
 * SECURITY: this module never sets `innerHTML`. Every dynamic value goes through
 * {@link esc} before it is interpolated into the returned markup strings, which
 * keeps external, workflow- or agent-controlled text inert.
 */

/** Escape a value for safe interpolation inside an HTML text node or attribute. */
export function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** Humanize a duration in milliseconds. `null`/`undefined` render as an em dash. */
export function fmtDur(ms) {
  if (ms === null || ms === undefined || !Number.isFinite(Number(ms))) return '—'
  const value = Number(ms)
  if (value < 1000) return `${value}ms`
  if (value < 60000) return `${(value / 1000).toFixed(1)}s`
  const minutes = Math.floor(value / 60000)
  const seconds = Math.round((value % 60000) / 1000)
  return `${minutes}m${String(seconds).padStart(2, '0')}s`
}

/**
 * Fact classification table. Presentation order, stable across renders.
 *
 * @type {ReadonlyArray<{ title: string, keys: ReadonlyArray<string> }>}
 */
export const FACT_GROUPS = Object.freeze([
  {
    title: 'Verdict',
    keys: Object.freeze(['exitCode', 'timedOut', 'command', 'commandDurationMs', 'tasks', 'domain']),
  },
  {
    title: 'Fichiers',
    keys: Object.freeze([
      'filesModified',
      'filesUntracked',
      'plannedFiles',
      'missingFiles',
      'actualFiles',
      'unplannedFiles',
      'untouchedPlannedFiles',
      'claimsMatch',
      'fileCount',
      'wroteNothing',
    ]),
  },
  {
    title: 'Tour d\u2019agent',
    keys: Object.freeze([
      'caseId',
      'agentStatus',
      'caseStatus',
      'agentsSelected',
      'agentTurns',
      'toolCallCount',
      'failedToolCalls',
      'killedByBudget',
      'anchored',
      'expectedAgent',
    ]),
  },
  {
    title: 'Contexte',
    keys: Object.freeze([
      'agentName',
      'analystName',
      'editorName',
      'subAgents',
      'analystSubAgents',
      'editorSubAgents',
      'rootPath',
      'ticketId',
      'summary',
      'revision',
      'attempt',
    ]),
  },
])

/**
 * Facts whose mere presence justifies a visual signal on the bar.
 *
 * @type {ReadonlyArray<{ key: string, when: (value: unknown) => boolean, icon: string, label: string, level: string }>}
 */
export const FLAGS = Object.freeze([
  {
    key: 'wroteNothing',
    when: (value) => value === true,
    icon: '\u2298',
    label: 'aucune écriture',
    level: 'bad',
  },
  {
    key: 'timedOut',
    when: (value) => value === true,
    icon: '\u23f1',
    label: 'timeout',
    level: 'bad',
  },
  {
    key: 'killedByBudget',
    when: (value) => value === true,
    icon: '\u2620',
    label: 'tué par budget',
    level: 'bad',
  },
  {
    key: 'claimsMatch',
    when: (value) => value === false,
    icon: '\u2260',
    label: 'plan ≠ diff',
    level: 'warn',
  },
  {
    key: 'missingFiles',
    when: (value) => Array.isArray(value) && value.length > 0,
    icon: '\u2717',
    label: 'fichiers absents',
    level: 'bad',
  },
])

/** Synthetic flag appended when a success executed nothing. */
export const EMPTY_SUCCESS_FLAG = Object.freeze({
  key: 'emptySuccess',
  icon: '\u26a0',
  label: 'succès vide — aucune tâche exécutée',
  level: 'warn',
})

const SUCCESS_STATUSES = new Set(['pass', 'completed'])

/**
 * Detect an "empty success": a positive verdict on a command that ran nothing.
 *
 * This is the on-screen materialization of the F10 guard — `exitCode: 0` served
 * entirely from cache is true and empty. The projection v2 step status
 * `completed` is accepted alongside the legacy `pass`.
 *
 * @param {{ status?: string, facts?: { tasks?: { executed?: number } } } | null | undefined} stepOrPhase
 * @returns {boolean}
 */
export function emptySuccess(stepOrPhase) {
  if (!stepOrPhase) return false
  if (!SUCCESS_STATUSES.has(stepOrPhase.status)) return false
  const tasks = stepOrPhase.facts?.tasks
  if (!tasks || typeof tasks !== 'object') return false
  return (tasks.executed ?? 0) === 0
}

/**
 * Collect the declared flags present in a step's facts.
 *
 * @param {{ facts?: Record<string, unknown> } | null | undefined} stepOrPhase
 * @returns {Array<{ key: string, icon: string, label: string, level: string }>}
 */
export function collectFlags(stepOrPhase) {
  const out = []
  const facts = stepOrPhase?.facts ?? {}
  for (const flag of FLAGS) {
    if (flag.key in facts && flag.when(facts[flag.key])) out.push(flag)
  }
  return out
}

/** Escaped rendering of a single fact value, typed by key. */
export function renderValue(key, value) {
  if (Array.isArray(value)) {
    if (value.length === 0) return '<span class="fv nil">(vide)</span>'
    const cls = String(key).toLowerCase().includes('file') ? 'tag path' : 'tag'
    return (
      '<div class="fv list">' +
      value.map((entry) => `<span class="${cls}">${esc(String(entry))}</span>`).join('') +
      '</div>'
    )
  }
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value)
    if (entries.length === 0) return '<span class="fv nil">(vide)</span>'
    return (
      '<div class="fv list">' +
      entries.map(([k, v]) => `<span class="tag">${esc(k)}: ${esc(String(v))}</span>`).join('') +
      '</div>'
    )
  }
  if (typeof value === 'boolean') {
    return `<span class="fv ${value ? 'yes' : 'no'}">${value}</span>`
  }
  if (String(key).endsWith('DurationMs') || key === 'durationMs') {
    return `<span class="fv">${esc(fmtDur(value))}</span>`
  }
  return `<span class="fv">${esc(String(value))}</span>`
}

/** One classified block of facts. */
function factBlock(title, entries) {
  const rows = entries.map(([key, value]) => `<span class="fk">${esc(key)}</span>${renderValue(key, value)}`).join('')
  return `<div class="fgroup"><div class="gt">${esc(title)}</div><div class="facts">${rows}</div></div>`
}

/**
 * Render a facts object as classified blocks. Unclassified keys stay visible.
 *
 * @param {Record<string, unknown> | null | undefined} facts
 * @returns {string} escaped markup
 */
export function renderFactGroups(facts) {
  if (!facts || typeof facts !== 'object') return '<span class="nil">Aucun fait enregistré.</span>'
  const remaining = new Set(Object.keys(facts))
  const blocks = []

  for (const group of FACT_GROUPS) {
    const present = group.keys.filter((key) => remaining.has(key))
    if (present.length === 0) continue
    for (const key of present) remaining.delete(key)
    blocks.push(
      factBlock(
        group.title,
        present.map((key) => [key, facts[key]])
      )
    )
  }

  // Anything unclassified stays visible. The default is visibility.
  if (remaining.size > 0) {
    blocks.push(
      factBlock(
        'Autres',
        [...remaining].map((key) => [key, facts[key]])
      )
    )
  }

  return blocks.join('') || '<span class="nil">Aucun fait enregistré.</span>'
}

export default {
  esc,
  fmtDur,
  FACT_GROUPS,
  FLAGS,
  EMPTY_SUCCESS_FLAG,
  emptySuccess,
  collectFlags,
  renderValue,
  renderFactGroups,
}
