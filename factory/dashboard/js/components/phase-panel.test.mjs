/**
 * Factory Cockpit — phase-panel human checkpoint unit tests (vanilla, Node
 * built-in test runner, zero dependencies, zero build step).
 *
 *   node --test factory/dashboard/js/components/phase-panel.test.mjs
 *
 * Covers the decision surface grafted into the phase inspector:
 *   - a human step awaiting an OPEN interaction renders the Approuver / Rejeter
 *     buttons and the optional comment field;
 *   - a human step awaiting but WITHOUT a loaded interaction renders no buttons;
 *   - a non-human or already-resolved step renders no decision card;
 *   - `loadPhaseEnrichment` fetches the open interactions and exposes the one
 *     waiting for the selected step.
 */

import test from 'node:test'
import assert from 'node:assert/strict'

import {
  renderPhasePanel,
  loadPhaseEnrichment,
  isHumanStep,
  findWaitingInteraction,
  humanCheckpoint,
  CHECKPOINT_COMMENT_MAX,
} from './phase-panel.mjs'

/** A normalized (Gantt) human step awaiting a human decision. */
function humanWaitingStep(overrides = {}) {
  return {
    id: 'gate',
    name: 'Approval gate',
    phaseKind: 'human',
    status: 'blocked',
    projectionStatus: 'waiting_human',
    responsibility: { kind: 'human', name: 'engineer' },
    facts: {},
    durationMs: 1000,
    ...overrides,
  }
}

function waitingInteraction(overrides = {}) {
  return {
    interactionId: 'i-1',
    workflowId: 'wf-1',
    stepId: 'gate',
    interactionType: 'human',
    status: 'waiting',
    revision: 3,
    prompt: 'Please review the change.',
    actions: [
      { id: 'approve', label: 'Approve' },
      { id: 'reject', label: 'Reject' },
    ],
    ...overrides,
  }
}

// --------------------------------------------------------------- classification

test('isHumanStep recognises the lane, phaseKind and responsibility kind', () => {
  assert.equal(isHumanStep({ phaseKind: 'human' }), true)
  assert.equal(isHumanStep({ lane: 'human' }), true)
  assert.equal(isHumanStep({ responsibility: { kind: 'human' } }), true)
  assert.equal(isHumanStep({ phaseKind: 'code' }), false)
  assert.equal(isHumanStep(null), false)
})

test('findWaitingInteraction only returns an OPEN interaction for the step', () => {
  const step = humanWaitingStep()
  assert.equal(findWaitingInteraction(step, { interaction: waitingInteraction() }).interactionId, 'i-1')
  // Resolved interactions are ignored.
  assert.equal(findWaitingInteraction(step, { interaction: waitingInteraction({ status: 'resolved' }) }), null)
  // Another step's interaction is ignored.
  assert.equal(findWaitingInteraction(step, { interaction: waitingInteraction({ stepId: 'other' }) }), null)
  assert.equal(findWaitingInteraction(step, null), null)
})

test('humanCheckpoint flags a human step waiting on an open interaction', () => {
  const state = humanCheckpoint(humanWaitingStep(), { interaction: waitingInteraction() })
  assert.equal(state.relevant, true)
  assert.equal(state.waiting, true)
  assert.equal(state.interaction.interactionId, 'i-1')

  const withoutInteraction = humanCheckpoint(humanWaitingStep(), null)
  assert.equal(withoutInteraction.relevant, true)
  assert.equal(withoutInteraction.waiting, true)
  assert.equal(withoutInteraction.interaction, null)

  const codeStep = humanCheckpoint({ id: 'b', phaseKind: 'code', status: 'running' }, null)
  assert.equal(codeStep.relevant, false)
  assert.equal(codeStep.waiting, false)
})

// ----------------------------------------------------------------- rendering

test('a human waiting step with an open interaction renders Approuver / Rejeter', () => {
  const html = renderPhasePanel({
    step: humanWaitingStep(),
    workflow: { projection: { title: 'Run 1' } },
    evidence: [],
    enrichment: { interaction: waitingInteraction() },
  })

  assert.ok(html.includes('data-checkpoint="true"'), 'expected the checkpoint card')
  assert.ok(html.includes('data-checkpoint-action="approve"'), 'expected the approve button')
  assert.ok(html.includes('data-checkpoint-action="reject"'), 'expected the reject button')
  assert.ok(html.includes('id="checkpoint-comment"'), 'expected the optional comment field')
  assert.ok(html.includes(`maxlength="${CHECKPOINT_COMMENT_MAX}"`), 'expected the 2000 char cap')
  assert.ok(html.includes('Please review the change.'), 'expected the interaction prompt')
  assert.ok(html.includes('Approuver'))
  assert.ok(html.includes('Rejeter'))
})

test('a human waiting step WITHOUT an open interaction renders no buttons', () => {
  const html = renderPhasePanel({
    step: humanWaitingStep(),
    workflow: null,
    evidence: [],
    enrichment: null,
  })

  assert.ok(html.includes('data-checkpoint="true"'), 'expected the checkpoint card')
  assert.ok(!html.includes('data-checkpoint-action="approve"'), 'expected no approve button')
  assert.ok(!html.includes('data-checkpoint-action="reject"'), 'expected no reject button')
  assert.ok(!html.includes('id="checkpoint-comment"'), 'expected no comment field')
})

test('a non-human step renders no decision card', () => {
  const html = renderPhasePanel({
    step: { id: 'b', name: 'build', phaseKind: 'code', status: 'completed', facts: {} },
    workflow: null,
    evidence: [],
    enrichment: { interaction: waitingInteraction({ stepId: 'b' }) },
  })

  assert.ok(!html.includes('data-checkpoint="true"'), 'expected no checkpoint card')
  assert.ok(!html.includes('data-checkpoint-action="approve"'), 'expected no approve button')
})

test('a resolved human step renders no card unless a feedback is pending', () => {
  const resolved = humanWaitingStep({ status: 'pass', projectionStatus: 'completed' })
  const withoutFeedback = renderPhasePanel({ step: resolved, workflow: null, evidence: [], enrichment: null })
  assert.ok(!withoutFeedback.includes('data-checkpoint-action="approve"'), 'expected no approve button')
  assert.ok(!withoutFeedback.includes('data-checkpoint="true"'), 'expected no card when resolved')

  const withFeedback = renderPhasePanel({
    step: resolved,
    workflow: null,
    evidence: [],
    enrichment: null,
    checkpoint: { submitting: false, feedback: { type: 'success', message: 'Checkpoint résolu.' } },
  })
  assert.ok(withFeedback.includes('data-checkpoint="true"'), 'expected the success feedback card')
  assert.ok(withFeedback.includes('data-checkpoint-feedback="success"'), 'expected a success feedback')
  assert.ok(!withFeedback.includes('data-checkpoint-action="approve"'), 'expected no button after resolution')
})

test('a submitting checkpoint disables the decision buttons', () => {
  const html = renderPhasePanel({
    step: humanWaitingStep(),
    workflow: null,
    evidence: [],
    enrichment: { interaction: waitingInteraction() },
    checkpoint: { submitting: true, feedback: null },
  })
  assert.ok(html.includes('data-checkpoint-action="approve" disabled'))
  assert.ok(html.includes('data-checkpoint-action="reject" disabled'))
})

// ------------------------------------------------------------- enrichment fetch

test('loadPhaseEnrichment fetches open interactions for a human step', async () => {
  const calls = []
  const apiClient = {
    async get(path) {
      calls.push(path)
      if (path.includes('/interactions')) {
        return {
          items: [
            waitingInteraction({ interactionId: 'i-resolved', stepId: 'gate', status: 'resolved' }),
            waitingInteraction({ interactionId: 'i-open', stepId: 'gate', status: 'waiting', revision: 7 }),
            waitingInteraction({ interactionId: 'i-other', stepId: 'other', status: 'waiting' }),
          ],
        }
      }
      throw new Error(`unexpected path ${path}`)
    },
  }

  const result = await loadPhaseEnrichment(humanWaitingStep(), {
    apiClient,
    workflowId: 'wf-1',
    namespaceId: 'ns-1',
  })

  assert.ok(calls.some((path) => path === '/api/factory/workflows/wf-1/interactions?namespaceId=ns-1'))
  assert.equal(result.interaction.interactionId, 'i-open')
  assert.equal(result.interaction.revision, 7)
})

test('loadPhaseEnrichment never throws when the interactions call fails', async () => {
  const apiClient = {
    async get() {
      throw new Error('boom')
    },
  }
  const result = await loadPhaseEnrichment(humanWaitingStep(), { apiClient, workflowId: 'wf-1' })
  assert.equal(result.interaction, null)
  assert.ok(result.notices.some((notice) => notice.kind === 'interaction'))
})

test('loadPhaseEnrichment skips interactions for a non-human step', async () => {
  let called = false
  const apiClient = {
    async get() {
      called = true
      return { items: [] }
    },
  }
  const result = await loadPhaseEnrichment(
    { id: 'build', name: 'build', phaseKind: 'code', status: 'running', facts: {} },
    { apiClient, workflowId: 'wf-1' },
  )
  assert.equal(called, false)
  assert.equal(result.interaction, null)
})
