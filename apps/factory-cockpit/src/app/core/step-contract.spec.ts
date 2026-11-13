import { extractAttempts } from './mappers/actions'
import {
  buildRoutingSection,
  buildPhaseDetail,
  mapProjectionToPhaseByAttemptId,
  stepContractSections,
} from './mappers/phase-detail'

describe('explicit step contracts', () => {
  it('builds a French routing section without fabricating routing for ordinary steps', () => {
    expect(buildRoutingSection({ id: 'ordinary' })).toBeNull()
    const section = buildRoutingSection({
      decision: {
        mode: 'single',
        routes: [
          { name: 'route-1', target: 'one' },
          { name: 'route-2', target: 'two' },
        ],
      },
      routeDecision: { selectedRoutes: ['route-1'], attemptId: 'a', resultId: 'r' },
      activation: 'selected',
      executed: true,
      join: 'decision',
    })
    expect(section?.label).toBe('Routage conditionnel')
    expect(section?.items).toEqual(
      expect.arrayContaining([
        { title: 'route-1', subtitle: 'one', status: 'selected' },
        { title: 'route-2', subtitle: 'two', status: 'not_selected' },
        { title: 'Exécutée', subtitle: 'oui' },
        { title: 'Tentative retenue', subtitle: 'a' },
      ])
    )
  })
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
    expect(stepContractSections(step).map((section) => section.label)).toEqual([
      'Préparation (configurée)',
      'Mission (configurée)',
      'Livrables (configurés)',
    ])
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
