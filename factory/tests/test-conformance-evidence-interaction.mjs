// Evidence & human-interaction persistence conformance suite (Milestone B3, task B3-T1).
//
// The exact same scenarios run against the filesystem adapters (reference
// implementation) and the new SQL adapters, backed by the shared in-memory
// `SqlClient`. That keeps the run offline and Docker-free while still exercising
// the SQL query construction, row mapping, idempotency and atomic unit of work.
//
// On top of the parity scenarios the suite asserts the SQL-only guarantees the
// filesystem backend cannot express:
//   * `workflow_evidence` is strictly append-only (no UPDATE / DELETE issued);
//   * `recordOpen` / `recordTransition` are atomic: a failure rolls back every
//     row (interaction, event, evidence, outbox) of the unit of work;
//   * the transactional outbox event is written inside the same transaction;
//   * `reconcileOpen` follows the same recovery rules as the filesystem store.
//
// Usage : node factory/tests/test-conformance-evidence-interaction.mjs
// Exit code : 0 = every case passed, 1 = at least one failure.

import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { mkdtemp, rm } from 'node:fs/promises'
import { register } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// Register the `.js` → `.ts` resolver before importing the TypeScript adapters
// directly (the runtime bundle does not yet re-export them: W3 owns that wiring).
register(new URL('./support/node-ts-resolve-hook.mjs', import.meta.url))

const {
  createFilesystemWorkflowEvidenceRepository,
  createFilesystemWorkflowHumanInteractionRepository,
  humanInteractionSemanticHash,
} = await import('../runtime/factory-operational.mjs')
const { WorkflowEvidenceStore } = await import('../lib/workflow-evidence-store.mjs')
const { WorkflowHumanInteractionStore } = await import('../lib/workflow-human-interaction-store.mjs')
const { SqlWorkflowEvidenceRepository } = await import(
  '../src/adapters/persistence/sql/sql-workflow-evidence-repository.ts'
)
const { SqlWorkflowHumanInteractionRepository } = await import(
  '../src/adapters/persistence/sql/sql-workflow-human-interaction-repository.ts'
)
const { createInMemorySqlClient } = await import('./support/in-memory-sql-client.mjs')

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

const NAMESPACE = '11111111-1111-4111-8111-111111111111'

let passed = 0
let failed = 0

async function test(name, fn) {
  try {
    await fn()
    console.log(`\u2713 ${name}`)
    passed++
  } catch (error) {
    console.error(`\u2717 ${name}\n   ${error?.stack ?? error}`)
    failed++
  }
}

const rows = async (client, table) => (await client.query(`SELECT * FROM ${table}`)).rows

/** In-memory client that records every statement it serves. */
function createRecordingClient() {
  const client = createInMemorySqlClient()
  const statements = []
  return {
    statements,
    query(text, params) {
      statements.push(text.replace(/\s+/g, ' ').trim())
      return client.query(text, params)
    },
  }
}

/** In-memory client that throws on statements matching `pattern` while armed. */
function createToggleFaultClient() {
  const client = createInMemorySqlClient()
  let pattern = null
  return {
    arm(candidate) {
      pattern = candidate
    },
    disarm() {
      pattern = null
    },
    query(text, params) {
      const sql = text.replace(/\s+/g, ' ').trim()
      if (pattern && pattern.test(sql)) throw new Error('INJECTED_FAULT')
      return client.query(text, params)
    },
  }
}

// ---------------------------------------------------------------------------
// Shared scenario fixtures
// ---------------------------------------------------------------------------

const HUMAN_ACTIONS = [
  { id: 'approve', label: 'Approve', requestedStatus: 'completed' },
  { id: 'reject', label: 'Reject', requestedStatus: 'failed' },
]

function humanInput(workflowId, overrides = {}) {
  return {
    workflowId,
    stepId: 'approve',
    expectedRevision: 2,
    kind: 'approval',
    prompt: 'Approve?',
    idempotencyKey: 'open-1',
    actions: HUMAN_ACTIONS,
    ...overrides,
  }
}

function evidenceScenarios(repository, { namespaceId, workflowId }) {
  const storageId = workflowId
  const source = { kind: 'factory', runtimeId: 'runtime-1', agentId: 'worker' }
  return [
    [
      'evidence: record / idempotent replay / collision / list parity',
      async () => {
        const input = {
          workflowId,
          stepId: 'build',
          kind: 'agent-result',
          outcome: 'pass',
          facts: { attempt: 1 },
          idempotencyKey: 'idem-1',
        }
        const first = await repository.record(namespaceId, storageId, input, source)
        assert.equal(first.created, true)
        assert.equal(first.idempotent, false)
        assert.match(first.evidence.evidenceId, /^[0-9a-f-]{36}$/)
        assert.ok(!Number.isNaN(Date.parse(first.evidence.observedAt)))
        assert.deepEqual(first.evidence.source, source)
        assert.equal(first.evidence.workflowId, workflowId)
        assert.equal(first.evidence.stepId, 'build')

        const replay = await repository.record(namespaceId, storageId, input, source)
        assert.equal(replay.created, false)
        assert.equal(replay.idempotent, true)
        assert.equal(replay.evidence.evidenceId, first.evidence.evidenceId)

        await assert.rejects(
          () => repository.record(namespaceId, storageId, { ...input, outcome: 'fail' }, source),
          (error) => error.code === 'IDEMPOTENCY_KEY_COLLISION'
        )

        const second = await repository.record(
          namespaceId,
          storageId,
          { ...input, stepId: 'verify', idempotencyKey: 'idem-2' },
          source
        )
        const listed = await repository.list(namespaceId, storageId)
        assert.equal(listed.length, 2)
        assert.deepEqual(
          listed.map((item) => item.evidenceId).sort(),
          [first.evidence.evidenceId, second.evidence.evidenceId].sort()
        )
        assert.ok(listed[0].observedAt <= listed[1].observedAt, 'evidence must be chronologically ordered')
        assert.deepEqual(
          (await repository.list(namespaceId, storageId, { stepId: 'build' })).map((item) => item.evidenceId),
          [first.evidence.evidenceId]
        )
        assert.deepEqual(await repository.list(namespaceId, storageId, { stepId: 'absent' }), [])
        assert.deepEqual(await repository.list(namespaceId, 'missing-storage'), [])
      },
    ],
  ]
}

function humanScenarios(repository, { namespaceId, prefix }) {
  const storageId = `${prefix}-open`
  const input = humanInput(storageId)
  return [
    [
      'human: open / replay / collision / reply / contract errors parity',
      async () => {
        const opened = await repository.recordOpen(namespaceId, storageId, input, {
          transition: async () => ({
            ok: true,
            changed: true,
            idempotent: false,
            snapshot: { revision: 3 },
            requestId: 'open-req',
          }),
        })
        assert.equal(opened.created, true)
        assert.equal(opened.idempotent, false)
        assert.equal(opened.interaction.status, 'open')
        assert.equal(opened.interaction.revision, 3)
        assert.equal((await repository.list(namespaceId, storageId, { openOnly: true })).length, 1)
        assert.deepEqual(
          (await repository.events(namespaceId, storageId)).map((event) => event.event),
          ['interaction_opening', 'interaction_opened']
        )

        const replay = await repository.recordOpen(namespaceId, storageId, input, {
          transition: async () => {
            throw new Error('must not transition twice')
          },
        })
        assert.equal(replay.created, false)
        assert.equal(replay.idempotent, true)
        assert.equal(replay.interaction.interactionId, opened.interaction.interactionId)

        await assert.rejects(
          () =>
            repository.recordOpen(
              namespaceId,
              storageId,
              { ...input, prompt: 'Changed' },
              {
                transition: async () => ({ ok: true }),
              }
            ),
          (error) => error.code === 'IDEMPOTENCY_KEY_COLLISION'
        )
        await assert.rejects(
          () =>
            repository.recordOpen(
              namespaceId,
              storageId,
              { ...input, interactionId: 'gate-2', idempotencyKey: 'open-2' },
              { transition: async () => ({ ok: true }) }
            ),
          (error) => error.code === 'INTERACTION_ALREADY_OPEN'
        )
        await assert.rejects(
          () => repository.recordOpen(namespaceId, storageId, { ...input, idempotencyKey: 'open-3' }),
          (error) => error.code === 'HUMAN_INTERACTION_TRANSITION_REQUIRED'
        )
        await assert.rejects(
          () =>
            repository.recordOpen(
              namespaceId,
              storageId,
              { workflowId: storageId },
              { transition: async () => ({ ok: true }) }
            ),
          (error) => error.code === 'INVALID_INTERACTION'
        )

        const replied = await repository.recordTransition(
          namespaceId,
          storageId,
          opened.interaction.interactionId,
          { actionId: 'approve' },
          'actor-1',
          'evidence-1',
          'reply-req',
          {
            action: async () => ({
              reply: { actionId: 'approve' },
              actorId: 'actor-1',
              evidenceId: 'evidence-1',
              transition: { ok: true, requestId: 'reply-req', snapshot: { revision: 4 } },
            }),
          }
        )
        assert.equal(replied.status, 'replied')
        assert.equal(replied.revision, 4)
        assert.equal((await repository.list(namespaceId, storageId, { openOnly: true })).length, 0)
        assert.deepEqual(
          (await repository.events(namespaceId, storageId)).map((event) => event.event),
          ['interaction_opening', 'interaction_opened', 'interaction_transitioned']
        )

        await assert.rejects(
          () =>
            repository.recordTransition(
              namespaceId,
              storageId,
              opened.interaction.interactionId,
              {},
              'actor-1',
              'evidence-1',
              'reply-req-2',
              { action: async () => ({ transition: { ok: true } }) }
            ),
          (error) => error.code === 'INTERACTION_CLOSED'
        )
      },
    ],
    [
      'human: transition contract errors parity',
      async () => {
        const storage = `${prefix}-errors`
        await assert.rejects(
          () => repository.recordTransition(namespaceId, storage, 'missing', {}, 'actor-1', 'evidence-1', 'req-1'),
          (error) => error.code === 'HUMAN_INTERACTION_ACTION_REQUIRED'
        )
        await assert.rejects(
          () =>
            repository.recordTransition(namespaceId, storage, 'missing', {}, 'actor-1', 'evidence-1', 'req-1', {
              action: async () => ({ transition: { ok: true } }),
            }),
          (error) => error.code === 'INTERACTION_NOT_FOUND'
        )
      },
    ],
  ]
}

async function runScenarios(scenarios) {
  let ok = 0
  let ko = 0
  for (const [name, fn] of scenarios) {
    try {
      await fn()
      console.log(`  \u2713 ${name}`)
      ok++
    } catch (error) {
      console.error(`  \u2717 ${name}\n     ${error?.stack ?? error}`)
      ko++
    }
  }
  return { ok, ko }
}

// ---------------------------------------------------------------------------
// SQL-only fixtures
// ---------------------------------------------------------------------------

async function seedSqlEvent(client, namespaceId, storageId, event) {
  const interactionId = event.interaction?.interactionId ?? event.interactionId ?? ''
  await client.query(
    `INSERT INTO human_interaction_events
       (organization_id, workstream_id, namespace_id, workflow_id, interaction_id, event_id, event_type, actor_id, payload, created_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
    [
      'default',
      'default',
      namespaceId,
      storageId,
      interactionId,
      randomUUID(),
      event.event,
      event.actorId ?? 'system',
      JSON.stringify(event),
      new Date().toISOString(),
    ]
  )
}

function openingInteraction(input) {
  return {
    interactionId: input.interactionId ?? 'gate-seeded',
    workflowId: input.workflowId,
    stepId: input.stepId,
    expectedRevision: input.expectedRevision,
    kind: input.kind,
    prompt: input.prompt,
    actions: input.actions,
    idempotencyKey: input.idempotencyKey,
    semanticHash: humanInteractionSemanticHash(input),
    openedAt: new Date().toISOString(),
  }
}

// ---------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------

const root = await mkdtemp(join(tmpdir(), 'factory-conformance-evidence-interaction-'))

try {
  // ------------------------------------------------------------------------
  // 1. Parity — filesystem (reference) vs SQL
  // ------------------------------------------------------------------------
  const filesystemEvidence = createFilesystemWorkflowEvidenceRepository(new WorkflowEvidenceStore(root))
  const filesystemHuman = createFilesystemWorkflowHumanInteractionRepository(new WorkflowHumanInteractionStore(root))
  const sqlEvidence = new SqlWorkflowEvidenceRepository(createInMemorySqlClient())
  const sqlHuman = new SqlWorkflowHumanInteractionRepository(createInMemorySqlClient())

  for (const [label, repository] of [
    ['filesystem', filesystemEvidence],
    ['sql', sqlEvidence],
  ]) {
    await test(`evidence parity [${label}]`, async () => {
      const result = await runScenarios(
        evidenceScenarios(repository, { namespaceId: NAMESPACE, workflowId: 'wf-evidence' })
      )
      assert.equal(result.ko, 0)
    })
  }

  for (const [label, repository] of [
    ['filesystem', filesystemHuman],
    ['sql', sqlHuman],
  ]) {
    await test(`human-interaction parity [${label}]`, async () => {
      const result = await runScenarios(
        humanScenarios(repository, { namespaceId: NAMESPACE, prefix: `wf-human-${label}` })
      )
      assert.equal(result.ko, 0)
    })
  }

  // ------------------------------------------------------------------------
  // 2. SQL-only: append-only evidence + tenant scoping
  // ------------------------------------------------------------------------
  await test('sql: workflow_evidence is append-only and tenant scoped', async () => {
    const client = createRecordingClient()
    const repository = new SqlWorkflowEvidenceRepository(client)
    const source = { kind: 'factory', runtimeId: 'runtime-1', agentId: 'worker' }
    await repository.record(
      NAMESPACE,
      'wf-append-only',
      {
        workflowId: 'wf-append-only',
        stepId: 'build',
        kind: 'agent-result',
        outcome: 'pass',
        facts: { attempt: 1 },
        idempotencyKey: 'a-1',
      },
      source
    )
    await repository.list(NAMESPACE, 'wf-append-only')

    const evidenceStatements = client.statements.filter((sql) => sql.includes('workflow_evidence'))
    assert.ok(
      evidenceStatements.some((sql) => sql.startsWith('INSERT INTO workflow_evidence')),
      'at least one INSERT must target workflow_evidence'
    )
    for (const sql of evidenceStatements) {
      assert.doesNotMatch(sql, /^(UPDATE|DELETE)\b/i, `append-only violated by: ${sql}`)
    }

    const stored = await rows(client, 'workflow_evidence')
    assert.equal(stored.length, 1)
    assert.equal(stored[0].organization_id, 'default')
    assert.equal(stored[0].workstream_id, 'default')
    assert.equal(stored[0].namespace_id, NAMESPACE)
  })

  // ------------------------------------------------------------------------
  // 3. SQL-only: successful open writes the interaction + events + outbox
  // ------------------------------------------------------------------------
  await test('sql: transactional outbox event is written inside the open transaction', async () => {
    const client = createInMemorySqlClient()
    const repository = new SqlWorkflowHumanInteractionRepository(client)
    const storage = 'wf-outbox'
    await repository.recordOpen(NAMESPACE, storage, humanInput(storage), {
      transition: async () => ({ ok: true, changed: true, snapshot: { revision: 3 }, requestId: 'open-req' }),
    })
    assert.equal((await rows(client, 'human_interaction_events')).length, 2)
    assert.equal((await rows(client, 'human_interactions')).length, 1)
    const outbox = await rows(client, 'outbox_events')
    assert.equal(outbox.length, 1)
    assert.equal(outbox[0].event_type, 'human_interaction.opened')
    assert.equal(outbox[0].status, 'pending')
    assert.equal(outbox[0].workstream_id, 'default')
  })

  // ------------------------------------------------------------------------
  // 4. SQL-only: rollback atomicity when the transition callback fails
  // ------------------------------------------------------------------------
  await test('sql: recordOpen rolls back interaction + events + outbox on failure', async () => {
    const client = createToggleFaultClient()
    const repository = new SqlWorkflowHumanInteractionRepository(client)
    client.arm(/INSERT INTO outbox_events/i)
    await assert.rejects(
      () =>
        repository.recordOpen(NAMESPACE, 'wf-atomic-open', humanInput('wf-atomic-open'), {
          transition: async () => ({ ok: true, changed: true, snapshot: { revision: 3 }, requestId: 'open-req' }),
        }),
      (error) => error.message === 'INJECTED_FAULT'
    )
    assert.deepEqual(await rows(client, 'human_interactions'), [])
    assert.deepEqual(await rows(client, 'human_interaction_events'), [])
    assert.deepEqual(await rows(client, 'outbox_events'), [])
  })

  // ------------------------------------------------------------------------
  // 5. SQL-only: rollback atomicity when replying (evidence + event + outbox)
  // ------------------------------------------------------------------------
  await test('sql: recordTransition rolls back the whole unit of work on failure', async () => {
    const client = createToggleFaultClient()
    const repository = new SqlWorkflowHumanInteractionRepository(client)
    const storage = 'wf-atomic-reply'
    const opened = await repository.recordOpen(NAMESPACE, storage, humanInput(storage), {
      transition: async () => ({ ok: true, changed: true, snapshot: { revision: 3 }, requestId: 'open-req' }),
    })

    client.arm(/INSERT INTO outbox_events/i)
    await assert.rejects(
      () =>
        repository.recordTransition(
          NAMESPACE,
          storage,
          opened.interaction.interactionId,
          { actionId: 'approve' },
          'actor-1',
          'evidence-1',
          'reply-req',
          {
            action: async () => ({
              reply: { actionId: 'approve' },
              actorId: 'actor-1',
              evidenceId: 'evidence-1',
              transition: { ok: true, requestId: 'reply-req', snapshot: { revision: 4 } },
              evidence: {
                evidenceId: 'evidence-1',
                namespaceId: NAMESPACE,
                workflowId: storage,
                stepId: 'approve',
                kind: 'human-decision',
                outcome: 'pass',
                facts: { actionId: 'approve' },
                source: { kind: 'human', runtimeId: 'factory-dashboard', actorId: 'actor-1' },
                observedAt: new Date().toISOString(),
              },
            }),
          }
        ),
      (error) => error.message === 'INJECTED_FAULT'
    )

    assert.deepEqual(await rows(client, 'workflow_evidence'), [])
    assert.equal((await rows(client, 'human_interaction_events')).length, 2, 'only the committed opening events remain')
    assert.equal((await rows(client, 'human_interactions')).length, 1)
    assert.equal((await rows(client, 'outbox_events')).length, 1, 'only the committed open outbox event remains')
    const openOnly = await repository.list(NAMESPACE, storage, { openOnly: true })
    assert.equal(openOnly.length, 1)
    assert.equal(openOnly[0].revision, 3, 'the rolled-back reply must not have advanced the revision')
  })

  // ------------------------------------------------------------------------
  // 6. SQL-only: reconcileOpen recovers a proved interrupted opening
  // ------------------------------------------------------------------------
  await test('sql: reconcileOpen finalises an interrupted opening proved by workflow facts', async () => {
    const client = createInMemorySqlClient()
    const repository = new SqlWorkflowHumanInteractionRepository(client)
    const storage = 'wf-reconcile-open'
    const input = humanInput(storage, { interactionId: 'gate-seeded' })
    await seedSqlEvent(client, NAMESPACE, storage, {
      event: 'interaction_opening',
      interaction: openingInteraction(input),
    })

    const recovered = await repository.reconcileOpen(
      NAMESPACE,
      storage,
      input,
      { revision: 3, instance: { steps: [{ id: 'approve', status: 'waiting_human' }] } },
      {
        workflowFacts: [
          {
            kind: 'transition_accepted',
            revision: 3,
            transitionDelta: { steps: [{ stepId: 'approve', status: { from: 'ready', to: 'waiting_human' } }] },
          },
        ],
      }
    )
    assert.equal(recovered.status, 'open')
    assert.equal(recovered.revision, 3)
    assert.deepEqual(
      (await repository.events(NAMESPACE, storage)).map((event) => event.event),
      ['interaction_opening', 'interaction_opened']
    )
  })

  await test('sql: reconcileOpen rejects an unproven transition and an unknown opening', async () => {
    const client = createInMemorySqlClient()
    const repository = new SqlWorkflowHumanInteractionRepository(client)
    const storage = 'wf-reconcile-unproven'
    const input = humanInput(storage, { interactionId: 'gate-seeded' })
    await seedSqlEvent(client, NAMESPACE, storage, {
      event: 'interaction_opening',
      interaction: openingInteraction(input),
    })

    await assert.rejects(
      () =>
        repository.reconcileOpen(
          NAMESPACE,
          storage,
          input,
          { revision: 3, instance: { steps: [{ id: 'approve', status: 'waiting_human' }] } },
          { workflowFacts: [] }
        ),
      (error) => error.code === 'INTERACTION_RECOVERY_TRANSITION_UNPROVEN'
    )
    await assert.rejects(
      () => repository.reconcileOpen(NAMESPACE, 'wf-reconcile-absent', input, { revision: 3, instance: { steps: [] } }),
      (error) => error.code === 'INTERACTION_RECOVERY_NOT_FOUND'
    )
  })

  // ------------------------------------------------------------------------
  // 7. Parity: reconcileOpen on an interrupted opening (filesystem reference)
  //    and the matching SQL seeding yield the same observable outcome.
  // ------------------------------------------------------------------------
  await test('reconcileOpen parity: a ready step abandons the stale opening on both adapters', async () => {
    const input = humanInput('wf-reconcile-ready', { interactionId: 'gate-ready' })
    const readySnapshot = { revision: 2, instance: { steps: [{ id: 'approve', status: 'ready' }] } }

    // Filesystem reference: a crash after the transition leaves an 'opening' state.
    const faultingStore = new WorkflowHumanInteractionStore(root, {
      fault: async (seam) => {
        if (seam === 'after-transition') throw new Error('simulated crash')
      },
    })
    const faultingRepository = createFilesystemWorkflowHumanInteractionRepository(faultingStore)
    await assert.rejects(() =>
      faultingRepository.recordOpen(NAMESPACE, 'wf-reconcile-ready', input, {
        transition: async () => ({ ok: true, changed: true, snapshot: { revision: 3 }, requestId: 'open-req' }),
      })
    )
    const filesystemRepository = createFilesystemWorkflowHumanInteractionRepository(
      new WorkflowHumanInteractionStore(root)
    )

    // SQL: seed the same interrupted 'opening' state directly.
    const client = createInMemorySqlClient()
    const sqlRepository = new SqlWorkflowHumanInteractionRepository(client)
    await seedSqlEvent(client, NAMESPACE, 'wf-reconcile-ready', {
      event: 'interaction_opening',
      interaction: openingInteraction(input),
    })

    const expected = { code: 'INTERACTION_RECOVERY_NOT_FOUND' }
    await assert.rejects(
      () => filesystemRepository.reconcileOpen(NAMESPACE, 'wf-reconcile-ready', input, readySnapshot),
      (error) => error.code === expected.code
    )
    await assert.rejects(
      () => sqlRepository.reconcileOpen(NAMESPACE, 'wf-reconcile-ready', input, readySnapshot),
      (error) => error.code === expected.code
    )
    assert.deepEqual(await sqlRepository.list(NAMESPACE, 'wf-reconcile-ready', { openOnly: true }), [])
  })
} finally {
  await rm(root, { recursive: true, force: true })
}

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
