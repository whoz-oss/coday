import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { updateForgeRunYamlAtomic } from './forge-run-yaml-updater'

describe('updateForgeRunYamlAtomic', () => {
  it.each([1, 2, 3, 4] as const)('updates authoritative gate_%s fields and preserves unrelated content', (gate) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-yaml-'))
    const file = path.join(dir, 'WZ-1.yaml')
    fs.writeFileSync(
      file,
      `ticket_id: WZ-1\n# keep me\nunknown_field: 'preserved'\ngate_${gate}:\n  started_at: null\n  decided_at: null\n  human_decision: null\nrun_outcome:\n  status: in-progress\n  branch: 'feature/kept'\n`
    )
    updateForgeRunYamlAtomic(file, {
      gate,
      decision: gate === 2 ? 'approved-with-changes' : 'approved',
      at: '2026-09-01T10:00:00Z',
      completeRun: gate === 4,
    })
    const result = fs.readFileSync(file, 'utf8')
    expect(result).toContain("started_at: '2026-09-01T10:00:00Z'")
    expect(result).toContain("decided_at: '2026-09-01T10:00:00Z'")
    expect(result).toContain(`human_decision: '${gate === 2 ? 'approved-with-changes' : 'approved'}'`)
    expect(result).toContain("unknown_field: 'preserved'")
    expect(result).toContain("branch: 'feature/kept'")
    expect(result).toContain(`status: ${gate === 4 ? 'completed' : 'in-progress'}`)
    expect(fs.readdirSync(dir).filter((name) => name.endsWith('.tmp'))).toEqual([])
    fs.rmSync(dir, { recursive: true, force: true })
  })
})
