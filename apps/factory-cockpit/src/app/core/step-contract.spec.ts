import { extractAttempts } from './mappers/actions'
import { buildPhaseDetail, mapProjectionToPhaseByAttemptId, stepContractSections } from './mappers/phase-detail'

describe('explicit step contracts', () => {
  const step = {
    id: 'implement',
    name: 'Implement',
    status: 'completed',
    entry: ['Inspect branch', 'Sync'],
    mission: 'Implement scoped work',
    exit: ['Commit', 'Push'],
    responsibility: { kind: 'agent', name: 'Dev' },
  }

  it('renders configured prose in Entry Mission Exit order without raw JSON', () => {
    expect(stepContractSections(step).map((section) => section.body)).toEqual([
      '• Inspect branch\n• Sync',
      'Implement scoped work',
      '• Commit\n• Push',
    ])
    expect(stepContractSections({ mission: 'Analyze', entry: [], exit: [] })[0]?.body).toBe(
      'Aucune obligation configurée.'
    )
  })

  it('keeps each frozen effective mandate distinct from current configuration and other retries', () => {
    const attempts = extractAttempts([
      { attemptId: 'a1', stepId: step.id, attemptNumber: 1, effectiveMandate: 'OLD\nMANDATE' },
      { attemptId: 'a2', stepId: step.id, attemptNumber: 2, effectiveMandate: 'NEW MANDATE' },
      { attemptId: 'a3', stepId: step.id, attemptNumber: 3 },
    ])
    const details = mapProjectionToPhaseByAttemptId([step], 'succeeded', [], attempts)
    const mandate = (id: string): string | undefined =>
      details.get(id)?.sections.find((section) => section.label === 'Mandat effectif de la tentative')?.body
    expect(mandate('a1')).toBe('OLD\nMANDATE')
    expect(mandate('a2')).toBe('NEW MANDATE')
    expect(mandate('a3')).toContain('Indisponible')
    expect(
      buildPhaseDetail([step], 'succeeded', [], attempts, [], step.id, 'unknown').sections.find(
        (section) => section.label === 'Mandat effectif de la tentative'
      )?.notAvailable
    ).toBe(true)
  })

  it('explains legacy defaults without fabricating a dispatched mandate', () => {
    const sections = buildPhaseDetail([{ id: 'legacy' }], 'queued').sections
    expect(sections[0]?.body).toContain('Contrat historique')
    expect(sections.find((section) => section.label === 'Mandat effectif de la tentative')?.notAvailable).toBe(true)
  })
})
