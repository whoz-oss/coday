import { appendFileSync, existsSync } from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
import { join } from 'node:path'
import { ensureForgeRunStore } from './forge-roots.mjs'
import { parseForgeLedger } from './forge-ledger.mjs'
import { domains } from './domains.mjs'
import { buildOracleCommand } from './oracle-command.mjs'
import { runCommand } from './oracle.mjs'
import { resolveFrontOraclePlan } from './forge-front-oracle-resolution.mjs'

export const STORY_ORACLE_POLICY_VERSION = 'forge-story-oracles-v1'
const fail = (code, message = code) => {
  const error = new Error(message)
  error.code = code
  throw error
}
export const isAllowedStoryOracleRequestBody = (body) =>
  !!body &&
  typeof body === 'object' &&
  !Array.isArray(body) &&
  Object.keys(body).every((key) => ['editId', 'expectedSpecHash', 'attempt'].includes(key))
const hash = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
const append = (path, event) => appendFileSync(path, `${JSON.stringify(event)}\n`, 'utf8')
const catalog = {
  'front.build': { domain: 'front', name: 'build' },
  'front.tests': { domain: 'front', name: 'tests' },
  'back.build': { domain: 'back', name: 'build' },
}
function resolve(ids) {
  const result = []
  for (const id of ids) {
    const entry = catalog[id]
    if (!entry) fail('STORY_ORACLE_CATALOG_INVALID', `Unknown oracle catalog id: ${id}`)
    const oracle = domains[entry.domain]?.oracles.find((item) => item.name === entry.name)
    if (!oracle) fail('STORY_ORACLE_CATALOG_INVALID', `Unavailable oracle catalog id: ${id}`)
    result.push({ id, oracle })
  }
  return result
}
export async function executeStoryOracles({
  roots,
  epicRunId,
  storyRunId,
  editId,
  expectedSpecHash,
  attempt = 1,
  executor = runCommand,
  commandResolver = buildOracleCommand,
  frontResolver = resolveFrontOraclePlan,
  hostMapRaw = process.env.FACTORY_FRONT_BUILD_HOST_MAP,
  now = () => new Date().toISOString(),
}) {
  if (!Number.isInteger(attempt) || attempt <= 0)
    fail('STORY_ORACLE_ATTEMPT_INVALID', 'attempt must be a positive integer.')
  const store = ensureForgeRunStore(roots),
    path = join(store, `${epicRunId}.jsonl`)
  if (!existsSync(path)) fail('STORY_ORACLE_RUN_NOT_FOUND', `Epic run ${epicRunId} has no ledger.`)
  const events = parseForgeLedger(path),
    start = events.find((e) => e.event === 'run_started' && e.runId === epicRunId),
    story = events.find((e) => e.event === 'story_run_created' && e.runId === storyRunId && e.parentRunId === epicRunId)
  if (!start || !story) fail('STORY_ORACLE_STORY_NOT_FOUND')
  if (
    events.some(
      (e) =>
        e.event === 'story_oracles_started' &&
        e.editId === editId &&
        !events.some((f) => f.event === 'story_g3_evaluated' && f.campaignId === e.campaignId)
    )
  )
    fail('STORY_ORACLE_ALREADY_RUNNING', 'A Story oracle campaign is already active.')
  if (events.some((e) => e.event === 'story_g3_evaluated' && e.editId === editId && e.attempt === attempt))
    fail('STORY_ORACLE_ATTEMPT_COLLISION', 'A terminal campaign already exists for this editId and attempt.')
  const g1 = events.find((e) => e.event === 'human_decision_recorded' && e.runId === epicRunId && e.gate === 'G1')
  if (g1?.decision?.outcome !== 'approved') fail('STORY_ORACLE_G1_NOT_APPROVED')
  const g2 = events.filter((e) => e.event === 'g2_evaluated' && e.runId === epicRunId && e.status === 'passed').at(-1)
  if (!g2 || g2.spec?.sha256 !== expectedSpecHash) fail('STORY_ORACLE_G2_NOT_PASSED')
  const edit = events.find(
    (e) => e.event === 'story_edit_finished' && e.editId === editId && e.storyRunId === storyRunId
  )
  if (!edit || edit.status !== 'finished' || edit.outcome !== 'finished' || edit.diffValidation?.status !== 'valid')
    fail('STORY_ORACLE_EDIT_NOT_VALID')
  let spec
  try {
    spec = (await import('./forge-spec.mjs')).loadForgeSpec({ specPath: g2.spec.path, roots, workItem: start.workItem })
  } catch (error) {
    if (error.code === 'G2_ORACLE_UNKNOWN')
      fail(
        'STORY_ORACLE_CATALOG_INVALID',
        'The persisted G2 spec references an oracle outside the StoryOracle catalog.'
      )
    throw error
  }
  if (spec.sha256 !== g2.spec.sha256) fail('STORY_ORACLE_SPEC_HASH_STALE')
  const entries = resolve(spec.frontmatter.oracles),
    files = [...(edit.filesModified ?? []), ...(edit.filesCreated ?? [])]
  let frontPlan
  try {
    if (entries.some((entry) => entry.id.startsWith('front.')))
      frontPlan = frontResolver({
        repoRoot: roots.repoRoot,
        files,
        hostMapRaw,
        buildTemplate: domains.front.oracles.find((item) => item.name === 'build').command,
        testsTarget: process.env.FACTORY_FRONT_TEST_TARGET ?? 'frontend-test',
        requireBuild: entries.some((entry) => entry.id === 'front.build'),
      })
  } catch (error) {
    const code = error.code ?? 'ORACLE_INFRASTRUCTURE'
    const campaignId = `oracle_${randomUUID()}`
    append(path, {
      schemaVersion: 1,
      event: 'story_oracles_started',
      campaignId,
      runId: epicRunId,
      storyRunId,
      editId,
      attempt,
      specHash: spec.sha256,
      policyVersion: STORY_ORACLE_POLICY_VERSION,
      at: now(),
    })
    append(path, {
      schemaVersion: 1,
      event: 'story_oracle_finished',
      campaignId,
      runId: epicRunId,
      storyRunId,
      editId,
      name: 'front.infrastructure',
      status: 'blocked',
      code,
      exitCode: null,
      durationMs: 0,
      commandHash: null,
      at: now(),
    })
    append(path, {
      schemaVersion: 1,
      event: 'story_g3_evaluated',
      campaignId,
      runId: epicRunId,
      storyRunId,
      editId,
      attempt,
      status: 'blocked',
      specHash: spec.sha256,
      policyVersion: STORY_ORACLE_POLICY_VERSION,
      at: now(),
    })
    return {
      campaignId,
      status: 'blocked',
      results: [
        { name: 'front.infrastructure', status: 'blocked', code, exitCode: null, durationMs: 0, commandHash: null },
      ],
    }
  }
  const campaignId = `oracle_${randomUUID()}`
  append(path, {
    schemaVersion: 1,
    event: 'story_oracles_started',
    campaignId,
    runId: epicRunId,
    storyRunId,
    editId,
    attempt,
    specHash: spec.sha256,
    policyVersion: STORY_ORACLE_POLICY_VERSION,
    at: now(),
  })
  const results = []
  for (const { id, oracle } of entries) {
    if (id === 'front.tests' && frontPlan.tests.command === null) {
      const result = {
        name: id,
        ownerProjects: frontPlan.tests.owners,
        ownersWithTestTarget: frontPlan.tests.ownersWithTestTarget,
        ownersWithoutTestTarget: frontPlan.tests.ownersWithoutTestTarget,
        buildHosts: [],
        target: frontPlan.tests.target,
        configuration: null,
        status: 'skipped',
        code: 'ORACLE_NO_TEST_TARGET',
        exitCode: null,
        durationMs: 0,
        commandHash: null,
      }
      append(path, {
        schemaVersion: 1,
        event: 'story_oracle_finished',
        campaignId,
        runId: epicRunId,
        storyRunId,
        editId,
        ...result,
        at: now(),
      })
      results.push(result)
      continue
    }
    let command
    try {
      command =
        id === 'front.build'
          ? frontPlan.build.command
          : id === 'front.tests'
            ? frontPlan.tests.command
            : id === 'back.build'
              ? {
                  noHost: true,
                  reason:
                    'back.build is AgentOS-specific in the current domains catalog and is unavailable for a generic target repo.',
                }
              : commandResolver(oracle, files, roots.repoRoot)
    } catch (error) {
      command = { noHost: true, reason: String(error) }
    }
    if (typeof command !== 'string') {
      const result = {
        name: id,
        status: 'blocked',
        code: 'ORACLE_INFRASTRUCTURE',
        exitCode: null,
        durationMs: 0,
        commandHash: null,
      }
      append(path, {
        schemaVersion: 1,
        event: 'story_oracle_finished',
        campaignId,
        runId: epicRunId,
        storyRunId,
        editId,
        ...result,
        at: now(),
      })
      results.push(result)
      break
    }
    let raw
    try {
      raw = executor(command, { cwd: roots.repoRoot, timeoutMs: 20 * 60 * 1000 })
    } catch (error) {
      raw = { exitCode: -1, timedOut: false, durationMs: 0, crashed: true }
    }
    const status = raw.timedOut || raw.crashed ? 'blocked' : raw.exitCode === 0 ? 'passed' : 'failed'
    const testFacts =
      id === 'front.tests'
        ? {
            ownersWithTestTarget: frontPlan.tests.ownersWithTestTarget,
            ownersWithoutTestTarget: frontPlan.tests.ownersWithoutTestTarget,
          }
        : {}
    const result = {
      name: id,
      ownerProjects: id === 'front.tests' ? frontPlan.tests.owners : (frontPlan?.owners ?? []),
      ...testFacts,
      buildHosts: id === 'front.build' ? (frontPlan?.build?.buildHosts ?? []) : [],
      target: id === 'front.build' ? 'build' : id === 'front.tests' ? frontPlan.tests.target : null,
      configuration: id === 'front.build' ? 'development' : null,
      status,
      code: raw.timedOut
        ? 'ORACLE_TIMEOUT'
        : raw.crashed
          ? 'ORACLE_CRASH'
          : raw.exitCode === 0
            ? 'ORACLE_PASS'
            : 'ORACLE_FAIL',
      exitCode: raw.exitCode,
      durationMs: raw.durationMs ?? 0,
      commandHash: hash(command),
    }
    append(path, {
      schemaVersion: 1,
      event: 'story_oracle_finished',
      campaignId,
      runId: epicRunId,
      storyRunId,
      editId,
      ...result,
      at: now(),
    })
    results.push(result)
    if (status !== 'passed') break
  }
  const complete =
    results.length === entries.length &&
    results.every(
      (result, index) =>
        result.name === entries[index].id && (result.status === 'passed' || result.code === 'ORACLE_NO_TEST_TARGET')
    )
  const status = results.some((r) => r.status === 'blocked') ? 'blocked' : complete ? 'passed' : 'failed'
  append(path, {
    schemaVersion: 1,
    event: 'story_g3_evaluated',
    campaignId,
    runId: epicRunId,
    storyRunId,
    editId,
    attempt,
    status,
    specHash: spec.sha256,
    policyVersion: STORY_ORACLE_POLICY_VERSION,
    at: now(),
  })
  return { campaignId, status, results }
}
