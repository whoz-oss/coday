import {
  barGeometry,
  buildTimeline,
  compactBarGeometry,
  COMPACT_THRESHOLD_WIDTH,
  effectiveSpan,
  partitionTimelineIntervals,
  phaseActor,
  scalePosition,
  TimelineModel,
} from './factory-run-timeline.models'

describe('Factory run timeline mapping', () => {
  it('maps non-agent phases to orchestrator and agentsSelected[0] before agentName', () => {
    const phases = [
      {
        name: 'preflight',
        phaseKind: 'code',
        status: 'pass',
        startedAt: '2026-01-01T00:00:00Z',
        durationMs: 1,
        facts: { agentName: 'ignored' },
      },
      {
        name: 'implement',
        phaseKind: 'agent',
        status: 'pass',
        startedAt: '2026-01-01T00:00:01Z',
        durationMs: 1,
        facts: { agentsSelected: ['FrontendImpl'], agentName: 'fallback' },
      },
      {
        name: 'review',
        phaseKind: 'agent',
        status: 'pass',
        startedAt: '2026-01-01T00:00:02Z',
        durationMs: 1,
        facts: { agentName: 'ReviewerImpl' },
      },
    ]
    expect(buildTimeline(phases).lanes.map((lane) => lane.actor)).toEqual([
      'orchestrator',
      'FrontendImpl',
      'ReviewerImpl',
    ])
    expect(phaseActor(phases[0])).toBe('orchestrator')
  })

  it('keeps the factual orchestrator lane first and agent lanes in stable actor order', () => {
    const model = buildTimeline([
      {
        name: 'frontend',
        phaseKind: 'agent',
        status: 'pass',
        startedAt: '2026-01-01T00:00:00Z',
        durationMs: 1,
        facts: { agentName: 'FrontendImpl' },
      },
      {
        name: 'prepare',
        phaseKind: 'code',
        status: 'pass',
        startedAt: '2026-01-01T00:00:01Z',
        durationMs: 1,
        facts: {},
      },
      {
        name: 'bmad',
        phaseKind: 'agent',
        status: 'pass',
        startedAt: '2026-01-01T00:00:02Z',
        durationMs: 1,
        facts: { agentName: 'BmadBuilderImpl' },
      },
    ])
    expect(model.lanes.map((lane) => lane.actor)).toEqual(['orchestrator', 'BmadBuilderImpl', 'FrontendImpl'])
  })

  it('omits phases without timestamps and keeps short spans visible', () => {
    const model = buildTimeline([
      { name: 'short', phaseKind: 'code', status: 'pass', startedAt: '2026-01-01T00:00:00Z', durationMs: 0, facts: {} },
      { name: 'bad', phaseKind: 'code', status: 'pass', startedAt: null, durationMs: null, facts: {} },
    ])
    expect(model.lanes).toHaveLength(1)
    expect(model.lanes[0].phases[0].width).toBeGreaterThanOrEqual(0.45)
  })

  it('partitions overlapping visual intervals deterministically with a visible gap', () => {
    const phases = buildTimeline([
      {
        name: 'first',
        phaseKind: 'code',
        status: 'pass',
        startedAt: '2026-01-01T00:00:00Z',
        durationMs: 5000,
        facts: {},
      },
      {
        name: 'second',
        phaseKind: 'code',
        status: 'pass',
        startedAt: '2026-01-01T00:00:01Z',
        durationMs: 1000,
        facts: {},
      },
      {
        name: 'third',
        phaseKind: 'code',
        status: 'pass',
        startedAt: '2026-01-01T00:00:06Z',
        durationMs: 1000,
        facts: {},
      },
    ]).lanes[0].phases
    const partition = partitionTimelineIntervals(phases, 7000, 'linear')
    expect(partition.levelCount).toBe(2)
    expect(partition.phases.map((phase) => [phase.phase.name, phase.level])).toEqual([
      ['first', 0],
      ['second', 1],
      ['third', 0],
    ])
  })

  it('provides monotonic geometry for supported scales and clamps bars to the canvas', () => {
    for (const scale of ['linear', 'sqrt', 'log'] as const) {
      expect(scalePosition(250, 1000, scale)).toBeGreaterThanOrEqual(0)
      expect(scalePosition(250, 1000, scale)).toBeLessThan(scalePosition(750, 1000, scale))
      const geometry = barGeometry(900, 2000, 1000, scale)
      expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
    }
  })

  describe('barGeometry boundary hardening', () => {
    it('never produces left + width > 100 even when start equals total', () => {
      const geometry = barGeometry(1000, 1000, 1000, 'sqrt')
      expect(geometry.left).toBeLessThanOrEqual(100 - 0.45)
      expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
    })

    it('never produces left + width > 100 when start exceeds total', () => {
      for (const scale of ['linear', 'sqrt', 'log'] as const) {
        const geometry = barGeometry(1500, 1600, 1000, scale)
        expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
        expect(geometry.left).toBeGreaterThanOrEqual(0)
      }
    })

    it('applies minimum width while still clamping right edge to 100', () => {
      const geometry = barGeometry(999, 999, 1000, 'linear', 2)
      expect(geometry.width).toBeGreaterThanOrEqual(2)
      expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
    })

    it('left is always >= 0 even when start is negative', () => {
      const geometry = barGeometry(-500, 100, 1000, 'sqrt')
      expect(geometry.left).toBeGreaterThanOrEqual(0)
    })
  })

  describe('compact-phase boundary contract', () => {
    // barGeometry returns percentages in [0, 100] that map directly onto .timeline__plot.
    // .timeline__plot has zero padding (margin-inline provides the visual gutter on the
    // non-positioning .timeline__track wrapper). Therefore 100% === full visible width
    // and left+width ≤ 100 is a hard invariant: no bar can escape the coordinate space.
    //
    // For phases near the right boundary, barGeometry clamps left ≤ 100-minimumWidth
    // and width ≤ 100-left, so the bar grows leftward rather than overflowing rightward.

    it('first phase starting at origin has left exactly 0', () => {
      const geometry = barGeometry(0, 1000, 5000, 'sqrt')
      expect(geometry.left).toBe(0)
      expect(geometry.width).toBeGreaterThan(0)
      expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
    })

    it('last phase ending exactly at span boundary has left+width exactly 100', () => {
      const geometry = barGeometry(4000, 5000, 5000, 'sqrt')
      expect(geometry.left + geometry.width).toBe(100)
    })

    it('minimum-width bar ending exactly at 100% stays within [0, 100]', () => {
      // start === end === total: zero-duration phase at the very end of the run.
      // left is clamped to 100-minimumWidth so left+width === 100 exactly.
      const geometry = barGeometry(5000, 5000, 5000, 'sqrt')
      expect(geometry.left).toBeGreaterThanOrEqual(0)
      expect(geometry.left).toBeLessThanOrEqual(100 - 0.45)
      expect(geometry.width).toBeGreaterThanOrEqual(0.45)
      expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
    })

    it('compact phase ending close to 100% (within 1%) stays within [0, 100]', () => {
      // Simulates verify-tests or claims-gate: a short phase whose natural right edge
      // would land at ~99.6% — well within the plot but previously clipped by the
      // padding-based gutter. Must not overflow.
      for (const scale of ['linear', 'sqrt', 'log'] as const) {
        const geometry = barGeometry(4990, 5000, 5000, scale)
        expect(geometry.left).toBeGreaterThanOrEqual(0)
        expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
        expect(geometry.width).toBeGreaterThanOrEqual(0.45)
      }
    })

    it('compact phase that starts and ends within the last 2% stays within [0, 100]', () => {
      // Covers the verify-tests-1-1 / claims-gate-1 scenario: a short phase near the
      // run end where minimum-width expansion would previously push it past the padded
      // container boundary.
      const geometry = barGeometry(4950, 4960, 5000, 'sqrt')
      expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
      expect(geometry.width).toBeGreaterThanOrEqual(0.45)
    })

    it('compact phase at left boundary (start=0, very short) stays within [0, 100]', () => {
      const geometry = barGeometry(0, 10, 5000, 'sqrt')
      expect(geometry.left).toBe(0)
      expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
      expect(geometry.width).toBeGreaterThanOrEqual(0.45)
    })

    it('left is exactly 0 across all supported scales for a phase starting at origin', () => {
      for (const scale of ['linear', 'sqrt', 'log'] as const) {
        expect(barGeometry(0, 500, 5000, scale).left).toBe(0)
      }
    })
  })

  describe('activePhaseIndex and live bar growth', () => {
    function makePhase(status: string, durationMs: number | null = null) {
      return { name: 'p', phaseKind: 'code', status, startedAt: '2026-01-01T00:00:00Z', durationMs, facts: {} }
    }

    it('activePhaseIndex is null for a terminal run', () => {
      const model: TimelineModel = buildTimeline([makePhase('pass', 1000), makePhase('pass', 500)])
      expect(model.activePhaseIndex).toBeNull()
    })

    it('activePhaseIndex points to the running phase', () => {
      const phases = [
        {
          name: 'gate',
          phaseKind: 'code',
          status: 'pass',
          startedAt: '2026-01-01T00:00:00Z',
          durationMs: 0,
          facts: {},
        },
        {
          name: 'work',
          phaseKind: 'agent',
          status: 'running',
          startedAt: '2026-01-01T00:00:01Z',
          durationMs: null,
          facts: { agentName: 'Dev' },
        },
      ]
      const model: TimelineModel = buildTimeline(phases)
      // 'work' is at original index 1
      expect(model.activePhaseIndex).toBe(1)
    })

    it('completed zero-duration gate does not become active', () => {
      const phases = [
        {
          name: 'plan-gate',
          phaseKind: 'code',
          status: 'pass',
          startedAt: '2026-01-01T00:00:00Z',
          durationMs: 0,
          facts: {},
        },
        {
          name: 'work',
          phaseKind: 'agent',
          status: 'running',
          startedAt: '2026-01-01T00:00:01Z',
          durationMs: null,
          facts: { agentName: 'Dev' },
        },
      ]
      const model: TimelineModel = buildTimeline(phases)
      const gateIndex = 0
      expect(model.activePhaseIndex).not.toBe(gateIndex)
    })

    it('activePhaseIndex is null when all phases have terminal status', () => {
      const phases = [makePhase('pass', 1000), makePhase('fail', 500)]
      expect(buildTimeline(phases).activePhaseIndex).toBeNull()
    })

    it('durationMs stored in TimelinePhase is 0 for a running phase with null durationMs from server', () => {
      // Confirms the root-cause: buildTimeline stores durationMs=0 for the active phase.
      // The component must NOT use this raw value for the live bar — it must use nowOffset instead.
      const phases = [
        {
          name: 'work',
          phaseKind: 'agent',
          status: 'running',
          startedAt: '2026-01-01T00:00:00Z',
          durationMs: null,
          facts: { agentName: 'Dev' },
        },
      ]
      const model: TimelineModel = buildTimeline(phases)
      expect(model.lanes[0].phases[0].durationMs).toBe(0)
      expect(model.activePhaseIndex).toBe(0)
    })

    it('active bar end equals nowOffset: bar right edge aligns with playhead', () => {
      // Simulate a run that started 5 000ms ago; only one running phase with durationMs=0.
      // We expect barGeometry(startOffsetMs=0, nowOffset=5000, span=5000) to produce width=100%.
      const nowOffset = 5000
      const span = 5000
      const geometry = barGeometry(0, nowOffset, span, 'sqrt')
      // The playhead sits at scalePosition(nowOffset, span, 'sqrt') * 100 = 100%.
      // The bar right edge must match: left + width === 100.
      expect(geometry.left + geometry.width).toBe(100)
    })

    it('active bar grows as clock advances: wider at t=8s than at t=3s', () => {
      const span = 10000
      const startOffsetMs = 0
      const geo3 = barGeometry(startOffsetMs, 3000, span, 'sqrt')
      const geo8 = barGeometry(startOffsetMs, 8000, span, 'sqrt')
      expect(geo8.left + geo8.width).toBeGreaterThan(geo3.left + geo3.width)
    })

    it('phase completion switches to persisted duration: durationMs from server replaces live end', () => {
      // Once a phase transitions to 'pass', buildTimeline uses its persisted durationMs.
      // activePhaseIndex becomes null so the component stops using nowOffset for that bar.
      const phases = [
        {
          name: 'work',
          phaseKind: 'agent',
          status: 'pass',
          startedAt: '2026-01-01T00:00:00Z',
          durationMs: 3000,
          facts: { agentName: 'Dev' },
        },
      ]
      const model: TimelineModel = buildTimeline(phases)
      expect(model.activePhaseIndex).toBeNull()
      expect(model.lanes[0].phases[0].durationMs).toBe(3000)
    })

    it('active bar boundary stays <= 100% even at maximum elapsed', () => {
      // nowOffset === span: bar should reach exactly 100% and not overflow.
      const span = 7000
      const geometry = barGeometry(0, span, span, 'sqrt')
      expect(geometry.left + geometry.width).toBeLessThanOrEqual(100)
    })
  })

  describe('compactBarGeometry', () => {
    it('returns compact kind for a zero-duration phase (minimum-width bar)', () => {
      const result = compactBarGeometry(5000, 5000, 5000, 'sqrt')
      expect(result.kind).toBe('compact')
    })

    it('returns normal kind for a wide phase (>= COMPACT_THRESHOLD_WIDTH%)', () => {
      // A phase spanning the full run is definitely >= 2% wide
      const result = compactBarGeometry(0, 5000, 5000, 'sqrt')
      expect(result.kind).toBe('normal')
    })

    it('compact phase at left edge (anchor ~0%) uses growDirection right', () => {
      const result = compactBarGeometry(0, 0, 5000, 'sqrt')
      expect(result.kind).toBe('compact')
      if (result.kind !== 'compact') return
      expect(result.anchorPercent).toBe(0)
      expect(result.growDirection).toBe('right')
    })

    it('compact phase at right edge (start = total) uses growDirection left', () => {
      const result = compactBarGeometry(5000, 5000, 5000, 'sqrt')
      expect(result.kind).toBe('compact')
      if (result.kind !== 'compact') return
      expect(result.anchorPercent).toBeGreaterThan(85)
      expect(result.growDirection).toBe('left')
    })

    it('compact phase near the middle uses growDirection center', () => {
      // A zero-duration phase at the midpoint of the run (2500ms / 5000ms)
      const result = compactBarGeometry(2500, 2500, 5000, 'sqrt')
      expect(result.kind).toBe('compact')
      if (result.kind !== 'compact') return
      expect(result.anchorPercent).toBeGreaterThan(15)
      expect(result.anchorPercent).toBeLessThan(85)
      expect(result.growDirection).toBe('center')
    })

    it('anchorPercent is always in [0, 100] for any start value', () => {
      for (const start of [0, 1000, 2500, 4999, 5000]) {
        const result = compactBarGeometry(start, start, 5000, 'sqrt')
        if (result.kind === 'compact') {
          expect(result.anchorPercent).toBeGreaterThanOrEqual(0)
          expect(result.anchorPercent).toBeLessThanOrEqual(100)
        }
      }
    })

    it('normal kind result left and width satisfy barGeometry contract (left+width<=100)', () => {
      // A phase wide enough to be normal
      const result = compactBarGeometry(0, 2500, 5000, 'sqrt')
      expect(result.kind).toBe('normal')
      if (result.kind !== 'normal') return
      expect(result.left + result.width).toBeLessThanOrEqual(100)
      expect(result.left).toBeGreaterThanOrEqual(0)
    })

    it('COMPACT_THRESHOLD_WIDTH is 2', () => {
      expect(COMPACT_THRESHOLD_WIDTH).toBe(2)
    })
  })

  describe('effectiveSpan', () => {
    it('returns modelSpan when runningElapsedMs is null (finished run)', () => {
      expect(effectiveSpan(5000, null)).toBe(5000)
    })

    it('returns modelSpan when runningElapsedMs <= modelSpan', () => {
      expect(effectiveSpan(5000, 3000)).toBe(5000)
    })

    it('returns runningElapsedMs when it exceeds modelSpan (live run ahead of last recorded phase)', () => {
      expect(effectiveSpan(5000, 8000)).toBe(8000)
    })

    it('returns modelSpan when runningElapsedMs equals modelSpan exactly', () => {
      expect(effectiveSpan(5000, 5000)).toBe(5000)
    })

    it('returns 0 when both modelSpan and runningElapsedMs are 0', () => {
      expect(effectiveSpan(0, 0)).toBe(0)
    })
  })
})
