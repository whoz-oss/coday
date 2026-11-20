import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve, join } from 'node:path'
import { spawnSync } from 'node:child_process'

// Exercise the real entry point without installing tools or running a build.
describe('Factory build whitelist', () => {
  const script = resolve(__dirname, 'factory-build.mjs')

  it('declares the real build command in the target manifest', () => {
    const manifest = JSON.parse(readFileSync(resolve(__dirname, '../factory/verification.json'), 'utf8'))
    expect(manifest.schemaVersion).toBe('1')
    expect(manifest.verifications.build).toEqual({
      command: 'node scripts/factory-build.mjs',
      timeoutMs: 1800000,
    })
  })

  it('refuses an unrelated directory instead of finding the hosting repository', () => {
    const root = mkdtempSync(join(tmpdir(), 'factory-build-'))
    try {
      const result = spawnSync(process.execPath, [script], { cwd: root, encoding: 'utf8' })
      expect(result.status).toBe(1)
      expect(result.stderr).toContain('Missing prerequisite in target repository: .git')
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
})
