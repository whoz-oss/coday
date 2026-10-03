import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { forgeRunRecon, runForgeRunReconCli } from './forge-run-recon'

describe('forge run reconnaissance', () => {
  let projectRoot: string

  beforeEach(() => {
    projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-run-recon-'))
    fs.mkdirSync(path.join(projectRoot, 'forge/bmad'), { recursive: true })
    fs.writeFileSync(
      path.join(projectRoot, 'forge/bmad/workstreams.toml'),
      '[workstreams.forge]\nname = "Forge"\nstatus = "planning"\nroot = "forge/bmad/workstreams/forge"\n'
    )
  })

  afterEach(() => fs.rmSync(projectRoot, { recursive: true, force: true }))

  const found = { status: 'found', ticket: { key: 'WZ-34447', summary: 'Forge Run Protocol' }, gate_markers: [] }
  const recon = (input = 'WZ-34447') => forgeRunRecon({ projectRoot, input, jira: found })
  const handoff = (body: string) => {
    const file = path.join(projectRoot, 'forge/state/handoffs/WZ-34447.md')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, body)
  }

  it('returns the same fresh-start result for positional ticket-only or ticket-plus-intent input', () => {
    expect(recon().mode).toBe('Fresh-start')
    const withIntent = recon('/forge-run WZ-34447 Make the entry point memorable')
    expect(withIntent.input).toEqual({
      raw: '/forge-run WZ-34447 Make the entry point memorable',
      ticket: 'WZ-34447',
      intent: 'Make the entry point memorable',
    })
    expect(recon()).toEqual(recon())
  })

  it('exposes epicKey in proposed_action when Jira ticket belongs to an epic', () => {
    const withEpic = forgeRunRecon({
      projectRoot,
      input: 'WZ-34447',
      jira: { status: 'found', ticket: { key: 'WZ-34447', summary: 'Story', epicKey: 'WZ-34386' }, gate_markers: [] },
    })
    expect(withEpic.proposed_action.epicKey).toBe('WZ-34386')
  })

  it('omits epicKey from proposed_action when Jira ticket has no epic', () => {
    expect(recon().proposed_action.epicKey).toBeUndefined()
  })

  it('rejects an epicKey that is not a valid Jira ticket ID', () => {
    expect(() =>
      forgeRunRecon({
        projectRoot,
        input: 'WZ-34447',
        jira: {
          status: 'found',
          ticket: { key: 'WZ-34447', summary: 'Story', epicKey: 'not-a-ticket' },
          gate_markers: [],
        },
      })
    ).toThrow('Jira ticket epicKey must be a valid Jira ticket ID')
  })

  it('rejects unknown Jira ticket fields while accepting optional epicKey', () => {
    expect(() =>
      forgeRunRecon({
        projectRoot,
        input: 'WZ-34447',
        jira: {
          status: 'found',
          ticket: { key: 'WZ-34447', summary: 'Story', epicKey: 'WZ-34386', unsupported: true },
          gate_markers: [],
        },
      })
    ).toThrow('Jira ticket contains unsupported field: unsupported')
  })

  it('starts Gate 1 safely from free-text intent without a Jira read', () => {
    const result = forgeRunRecon({
      projectRoot,
      input: 'Create a memorable entry point for product discovery',
      jira: { status: 'not_requested' },
    })
    expect(result.input).toEqual({
      raw: 'Create a memorable entry point for product discovery',
      ticket: null,
      intent: 'Create a memorable entry point for product discovery',
    })
    expect(result.mode).toBe('Fresh-start')
    expect(result.proposed_action).toEqual({ kind: 'propose_gate_1', gate: 1, requires_confirmation: true })
  })

  it('requires not_requested Jira status for free-text intent', () => {
    expect(() => forgeRunRecon({ projectRoot, input: 'Product intent', jira: found })).toThrow(
      'free-text intent requires Jira status not_requested'
    )
    expect(() => forgeRunRecon({ projectRoot, input: 'WZ-34447', jira: { status: 'not_requested' } })).toThrow(
      'ticket input requires a normalized Jira read result'
    )
  })

  it('resumes a valid handoff and forwards only registry-derived paths', () => {
    const artifact = path.join(projectRoot, 'forge/bmad/workstreams/forge/planning-artifacts/prds/prd.md')
    fs.mkdirSync(path.dirname(artifact), { recursive: true })
    fs.writeFileSync(artifact, '# Forge PRD\n')
    handoff(
      '---\nticket_id: WZ-34447\nworkstream: forge\nlast_completed_gate: gate_1\nartifact_paths:\n  - forge/bmad/workstreams/forge/planning-artifacts/prds/prd.md\nnext_action: Continue Gate 2\n---\n'
    )
    const result = recon()
    expect(result.mode).toBe('Resume')
    expect(result.proposed_action).toEqual({ kind: 'resume_handoff', gate: 2, requires_confirmation: true })
    expect(result.evidence.bmad.allowed_paths).toEqual([artifact])
  })

  it('falls through corrupt handoff evidence to fresh start', () => {
    handoff('---\nticket_id: WZ-34447\nworkstream: forge\n---\n')
    const result = recon()
    expect(result.mode).toBe('Fresh-start')
    expect(result.warnings).toContain('ignored corrupt handoff evidence')
  })

  it('uses reconstruction when only a valid prior producer run record exists', () => {
    const file = path.join(projectRoot, 'forge/state/forge-runs/WZ-34447.yaml')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, '# Gate 1 closed at now\n# verdict: pass\n# human_decision: approved\n')
    expect(recon().mode).toBe('Reconstruct')
  })

  it('uses validated Jira gate markers before run-record inference', () => {
    const file = path.join(projectRoot, 'forge/state/forge-runs/WZ-34447.yaml')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, 'gate_1:\n  started_at: now\n')
    const result = forgeRunRecon({
      projectRoot,
      input: 'WZ-34447',
      jira: { ...found, gate_markers: ['gate_2'] },
    })
    expect(result.mode).toBe('Reconstruct')
    expect(result.evidence.run.last_completed_gate).toBe('gate_2')
    expect(result.proposed_action).toEqual({
      kind: 'reconstruct_from_jira_markers',
      gate: 3,
      requires_confirmation: true,
    })
  })

  it('discovers a ticket-prefixed BMad story without Forge handoff or run evidence', () => {
    const story = path.join(projectRoot, 'forge/bmad/workstreams/forge/implementation-artifacts/stories/story.md')
    fs.mkdirSync(path.dirname(story), { recursive: true })
    fs.writeFileSync(story, '---\nid: WZ-34447-routing\n---\n# Story\n')
    const result = recon()
    expect(result.mode).toBe('BMad-partial')
    expect(result.evidence.bmad.workstream).toBe('forge')
    expect(result.evidence.bmad.allowed_paths).toEqual([story])
  })

  it('discovers jira-owned planning evidence and nested epic stories without advancing a gate', () => {
    const planning = path.join(projectRoot, 'forge/bmad/workstreams/forge/planning-artifacts/spec.md')
    const nested = path.join(
      projectRoot,
      'forge/bmad/workstreams/forge/implementation-artifacts/epics/epic-WZ-34447/stories/story.md'
    )
    fs.mkdirSync(path.dirname(planning), { recursive: true })
    fs.mkdirSync(path.dirname(nested), { recursive: true })
    fs.writeFileSync(planning, '---\njira: WZ-34447\n---\n# Spec\n')
    fs.writeFileSync(nested, '---\nepic: WZ-34447\n---\n# Story\n')
    const result = recon()
    expect(result.mode).toBe('BMad-partial')
    expect(result.proposed_action).toEqual({ kind: 'propose_gate_1', gate: 1, requires_confirmation: true })
    expect(result.evidence.bmad.allowed_paths).toEqual([nested, planning].sort())
    expect(result.warnings).toContain(`noncanonical nested BMad artifact layout: ${nested}`)
  })

  it('accepts a Jira-owned story with distinct internal BMad story and epic identifiers', () => {
    const story = path.join(projectRoot, 'forge/bmad/workstreams/forge/implementation-artifacts/stories/astra.md')
    fs.mkdirSync(path.dirname(story), { recursive: true })
    fs.writeFileSync(story, '---\nid: TP-S-012\nepic: TP-E-005\njira: WZ-34447\n---\n# Astra\n')

    const result = recon()

    expect(result.mode).toBe('BMad-partial')
    expect(result.evidence.bmad.workstream).toBe('forge')
    expect(result.evidence.bmad.allowed_paths).toEqual([story])
    expect(result.warnings).not.toContain(`ignored contradictory ticket metadata: ${story}`)
  })

  it('returns a sorted deduplicated union of direct and nested structured artifacts', () => {
    const direct = path.join(projectRoot, 'forge/bmad/workstreams/forge/implementation-artifacts/stories/direct.md')
    const nested = path.join(
      projectRoot,
      'forge/bmad/workstreams/forge/implementation-artifacts/epics/epic-WZ-34447/stories/nested.md'
    )
    fs.mkdirSync(path.dirname(direct), { recursive: true })
    fs.mkdirSync(path.dirname(nested), { recursive: true })
    fs.writeFileSync(direct, '---\nticket: WZ-34447\n---\n# Direct\n')
    fs.writeFileSync(nested, '---\nid: WZ-34447-nested\n---\n# Nested\n')
    const result = recon()
    expect(result.evidence.bmad.allowed_paths).toEqual([direct, nested].sort())
    expect(result.warnings).toContain(`noncanonical nested BMad artifact layout: ${nested}`)
  })

  it('rejects contradictory metadata and prose-only ticket mentions', () => {
    const contradictory = path.join(projectRoot, 'forge/bmad/workstreams/forge/planning-artifacts/conflict.md')
    const prose = path.join(projectRoot, 'forge/bmad/workstreams/forge/planning-artifacts/prose.md')
    fs.mkdirSync(path.dirname(contradictory), { recursive: true })
    fs.writeFileSync(contradictory, '---\nticket: WZ-34447\njira: WZ-2\n---\n# Conflict\n')
    fs.writeFileSync(prose, '# Notes\n\nWZ-34447 is mentioned only as prose.\n')
    const result = recon()
    expect(result.mode).toBe('Fresh-start')
    expect(result.evidence.bmad.allowed_paths).toEqual([])
    expect(result.warnings).toContain(`ignored contradictory ticket metadata: ${contradictory}`)
  })

  it('rejects non-scalar, conflicting, or duplicate ownership metadata', () => {
    const directory = path.join(projectRoot, 'forge/bmad/workstreams/forge/implementation-artifacts/stories')
    fs.mkdirSync(directory, { recursive: true })
    for (const [name, body] of [
      ['non-scalar.md', '---\nticket: WZ-34447\njira:\n  - WZ-2\n---\n'],
      ['secondary-conflict.md', '---\nticket: WZ-34447\nepic: WZ-2\n---\n'],
      ['duplicate.md', '---\nticket: WZ-2\nticket: WZ-34447\n---\n'],
    ])
      fs.writeFileSync(path.join(directory, name), body)
    const result = recon()
    expect(result.mode).toBe('Fresh-start')
    expect(result.warnings).toContain(`ignored contradictory ticket metadata: ${path.join(directory, 'non-scalar.md')}`)
    expect(result.warnings).toContain(`ignored duplicate ticket metadata: ${path.join(directory, 'duplicate.md')}`)
  })

  it('ignores malformed run evidence instead of reconstructing from it', () => {
    const file = path.join(projectRoot, 'forge/state/forge-runs/WZ-34447.yaml')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, 'untrusted: injected')
    const result = recon()
    expect(result.mode).toBe('Fresh-start')
    expect(result.evidence.run.state).toBe('invalid')
    expect(result.warnings).toContain('ignored malformed run evidence')
  })

  it('treats a completed producer Gate 4 run as a fresh start', () => {
    const file = path.join(projectRoot, 'forge/state/forge-runs/WZ-34447.yaml')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, '# Gate 4 closed at now\n# human_decision: approved\n# run_outcome: completed\n')
    const result = recon()
    expect(result.mode).toBe('Fresh-start')
    expect(result.warnings).toContain('completed Gate 4 evidence starts a fresh run')
  })

  it('halts missing Jira and returns an explicit waiting result when Jira is unavailable', () => {
    expect(() => forgeRunRecon({ projectRoot, input: 'WZ-34447', jira: { status: 'missing' } })).toThrow(
      'Jira ticket missing'
    )
    handoff(
      '---\nticket_id: WZ-34447\nworkstream: forge\nlast_completed_gate: gate_1\nartifact_paths: []\nnext_action: Continue Gate 2\n---\n'
    )
    const unavailable = forgeRunRecon({
      projectRoot,
      input: 'WZ-34447',
      jira: { status: 'unavailable', reason: 'timeout' },
    })
    expect(unavailable.proposed_action).toEqual({
      kind: 'await_jira_confirmation',
      gate: null,
      requires_confirmation: true,
    })
    expect(unavailable.mode).toBe('Waiting')
    expect(unavailable.summary).toContain('Jira is unavailable')
  })

  it('drops unsafe handoff paths without forwarding them', () => {
    handoff(
      '---\nticket_id: WZ-34447\nworkstream: forge\nlast_completed_gate: gate_1\nartifact_paths:\n  - /tmp/injected.md\nnext_action: Continue Gate 2\n---\n'
    )
    const result = recon()
    expect(result.warnings).toContain('dropped unsafe BMad artifact path: /tmp/injected.md')
    expect(result.evidence.bmad.allowed_paths).not.toContain('/tmp/injected.md')
  })

  it('rejects malformed Jira result and reports CLI errors on stderr', () => {
    expect(() => forgeRunRecon({ projectRoot, input: 'WZ-34447 WZ-2', jira: found })).toThrow('multiple tickets')
    const stderr = jest.spyOn(process.stderr, 'write').mockImplementation(() => true)
    expect(runForgeRunReconCli(['--input', 'WZ-34447', '--jira-result', '{"status":"found"}'], projectRoot)).toBe(1)
    expect(stderr).toHaveBeenCalledWith(expect.stringContaining('closed normalized schema'))
    stderr.mockRestore()
  })

  it('rejects malformed or unordered Jira gate markers', () => {
    expect(() =>
      forgeRunRecon({ projectRoot, input: 'WZ-34447', jira: { ...found, gate_markers: ['gate_2', 'gate_1'] } })
    ).toThrow('gate_markers must be unique and ordered')
    expect(() =>
      forgeRunRecon({ projectRoot, input: 'WZ-34447', jira: { ...found, gate_markers: ['Gate 1'] } })
    ).toThrow('gate_markers must contain only')
  })

  it('accepts non-contiguous historical Jira markers', () => {
    const result = forgeRunRecon({
      projectRoot,
      input: 'WZ-34447',
      jira: { ...found, gate_markers: ['gate_1', 'gate_3'] },
    })
    expect(result.proposed_action).toEqual({
      kind: 'reconstruct_from_jira_markers',
      gate: 4,
      requires_confirmation: true,
    })
  })

  it('falls back to registered structured evidence after an unregistered handoff', () => {
    handoff(
      '---\nticket_id: WZ-34447\nworkstream: unknown\nlast_completed_gate: gate_1\nartifact_paths: []\nnext_action: Continue\n---\n'
    )
    const story = path.join(projectRoot, 'forge/bmad/workstreams/forge/implementation-artifacts/stories/story.md')
    fs.mkdirSync(path.dirname(story), { recursive: true })
    fs.writeFileSync(story, '---\nid: WZ-34447-routing\n---\n# Story\n')
    const result = recon()
    expect(result.mode).toBe('BMad-partial')
    expect(result.evidence.bmad.allowed_paths).toEqual([story])
    expect(result.warnings).toContain('ignored unregistered handoff evidence')
  })

  it('requires a workstream choice when ticket evidence appears in multiple registered roots', () => {
    fs.appendFileSync(
      path.join(projectRoot, 'forge/bmad/workstreams.toml'),
      '[workstreams.beta]\nname = "Beta"\nstatus = "planning"\nroot = "forge/bmad/workstreams/beta"\n'
    )
    for (const slug of ['forge', 'beta']) {
      const story = path.join(projectRoot, `forge/bmad/workstreams/${slug}/implementation-artifacts/stories/story.md`)
      fs.mkdirSync(path.dirname(story), { recursive: true })
      fs.writeFileSync(story, '---\nid: WZ-34447-routing\n---\n# Story\n')
    }
    const result = recon()
    expect(result.proposed_action).toEqual({ kind: 'select_bmad_workstream', gate: null, requires_confirmation: true })
    expect(result.evidence.bmad.allowed_paths).toEqual([])
  })

  it('recognizes producer-shaped Gate 1 through Gate 4 comments and ignores incomplete fragments', () => {
    const file = path.join(projectRoot, 'forge/state/forge-runs/WZ-34447.yaml')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, '# Gate 1 closed at now\n# verdict: pass\n# human_decision: approved-with-changes\n')
    expect(recon().mode).toBe('Reconstruct')
    fs.writeFileSync(file, '# Gate 4 closed at now\n# human_decision: approved\n# run_outcome: completed\n')
    expect(recon().mode).toBe('Fresh-start')
    fs.writeFileSync(
      file,
      '# Gate 1 closed at now\n# human_decision: approved\n# incomplete: fragment\nnot a producer record\n'
    )
    const result = recon()
    expect(result.mode).toBe('Fresh-start')
    expect(result.warnings).toContain('ignored malformed run evidence')
  })

  it('uses the latest valid Gate 2 or Gate 3 producer record for reconstruction', () => {
    const file = path.join(projectRoot, 'forge/state/forge-runs/WZ-34447.yaml')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(
      file,
      '# Gate 2 closed at now\n# branch: codex/WZ-34447\n# human_decision: approved\n# Gate 3 closed at now\n# verdict: pass\n# human_decision: approved-with-changes\n'
    )
    const result = recon()
    expect(result.mode).toBe('Reconstruct')
    expect(result.evidence.run.last_completed_gate).toBe('gate_3')
    expect(result.proposed_action).toEqual({ kind: 'reconstruct_handoff', gate: 4, requires_confirmation: true })
  })

  it('reconstructs when a later valid Gate 1 starts a run after a completed Gate 4', () => {
    const file = path.join(projectRoot, 'forge/state/forge-runs/WZ-34447.yaml')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(
      file,
      '# Gate 4 closed at now\n# human_decision: approved\n# run_outcome: completed\n# Gate 1 closed at later\n# verdict: pass\n# human_decision: approved\n'
    )
    const result = recon()
    expect(result.mode).toBe('Reconstruct')
    expect(result.evidence.run).toMatchObject({ completed: false, last_completed_gate: 'gate_1' })
    expect(result.proposed_action).toEqual({ kind: 'reconstruct_handoff', gate: 2, requires_confirmation: true })
  })

  it('selects structured Forge ownership and ignores a prose citation in another workstream', () => {
    fs.appendFileSync(
      path.join(projectRoot, 'forge/bmad/workstreams.toml'),
      '[workstreams.beta]\nname = "Beta"\nstatus = "planning"\nroot = "forge/bmad/workstreams/beta"\n'
    )
    const planning = path.join(projectRoot, 'forge/bmad/workstreams/forge/planning-artifacts/notes.md')
    const unrelated = path.join(projectRoot, 'forge/bmad/workstreams/beta/planning-artifacts/notes.md')
    fs.mkdirSync(path.dirname(planning), { recursive: true })
    fs.mkdirSync(path.dirname(unrelated), { recursive: true })
    fs.writeFileSync(unrelated, '# Notes\n\nWZ-34447 is mentioned only as context.\n')
    fs.writeFileSync(planning, '---\nticket: WZ-34447\n---\n# Owned note\n')
    const result = recon()
    expect(result.mode).toBe('BMad-partial')
    expect(result.evidence.bmad.workstream).toBe('forge')
    expect(result.evidence.bmad.allowed_paths).toEqual([planning])
  })

  it('warns and continues when a state path is unreadable', () => {
    const unreadable = path.join(projectRoot, 'forge/state/forge-runs/WZ-34447.yaml')
    fs.mkdirSync(unreadable, { recursive: true })
    const result = recon()
    expect(result.mode).toBe('Fresh-start')
    expect(result.warnings).toContain('ignored unreadable run evidence')
  })

  it('does not traverse symlinked BMad directories', () => {
    const external = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-run-symlink-'))
    fs.writeFileSync(path.join(external, 'story.md'), 'WZ-34447')
    const stories = path.join(projectRoot, 'forge/bmad/workstreams/forge/implementation-artifacts/stories')
    fs.mkdirSync(stories, { recursive: true })
    fs.symlinkSync(external, path.join(stories, 'linked-stories'), 'dir')
    expect(recon().mode).toBe('Fresh-start')
    fs.rmSync(external, { recursive: true, force: true })
  })

  it('emits a JSON recon result from the CLI adapter', () => {
    const stdout = jest.spyOn(process.stdout, 'write').mockImplementation(() => true)
    expect(runForgeRunReconCli(['--input', 'WZ-34447', '--jira-result', JSON.stringify(found)], projectRoot)).toBe(0)
    expect(JSON.parse(String(stdout.mock.calls[0][0]))).toMatchObject({ ok: true, mode: 'Fresh-start' })
    stdout.mockRestore()
  })

  it('rejects unknown, repeated, and valueless CLI flags', () => {
    const stderr = jest.spyOn(process.stderr, 'write').mockImplementation(() => true)
    expect(runForgeRunReconCli(['--unknown', 'x'], projectRoot)).toBe(1)
    expect(runForgeRunReconCli(['--input', 'WZ-34447', '--input', 'WZ-34447'], projectRoot)).toBe(1)
    expect(runForgeRunReconCli(['--input'], projectRoot)).toBe(1)
    stderr.mockRestore()
  })
})
