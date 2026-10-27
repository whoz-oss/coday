import * as fs from 'node:fs/promises'
import * as os from 'node:os'
import * as path from 'node:path'
import { Interactor } from '@coday/model'
import { runBash } from './run-bash'

describe('runBash', () => {
  let tmpDir: string
  let interactor: Interactor

  beforeEach(async () => {
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'coday-run-bash-'))
    await fs.mkdir(path.join(tmpDir, 'sub'))
    interactor = {
      debug: jest.fn(),
      error: jest.fn(),
      chooseOption: jest.fn(),
    } as unknown as Interactor
  })

  afterEach(async () => {
    await fs.rm(tmpDir, { recursive: true, force: true })
  })

  it('reports the working directory on success', async () => {
    const result = await runBash({ command: 'echo hello', root: tmpDir, interactor })
    expect(result.startsWith('Output:\n')).toBe(true)
    expect(result).toContain('hello')
    expect(result.endsWith(`Working directory: ${path.resolve(tmpDir)}`)).toBe(true)
  })

  it('reports the relPath sub-directory as working directory', async () => {
    const result = await runBash({ command: 'echo hi', root: tmpDir, relPath: 'sub', interactor })
    expect(result.endsWith(`Working directory: ${path.resolve(tmpDir, 'sub')}`)).toBe(true)
  })

  it('keeps the "Command failed:" prefix and reports the working directory on failure', async () => {
    const result = await runBash({ command: 'exit 3', root: tmpDir, interactor })
    expect(result.startsWith('Command failed:')).toBe(true)
    expect(result.endsWith(`Working directory: ${path.resolve(tmpDir)}`)).toBe(true)
    expect(interactor.error).toHaveBeenCalledWith(result)
  })

  it('refuses to run outside the root folder', async () => {
    const result = await runBash({ command: 'echo nope', root: path.join(tmpDir, 'sub'), relPath: '..', interactor })
    expect(result).toBe('Attempt to navigate outside the root folder')
  })

  it('refuses sibling folders sharing the root prefix', async () => {
    await fs.mkdir(path.join(tmpDir, 'sub-other'))
    const result = await runBash({
      command: 'echo nope',
      root: path.join(tmpDir, 'sub'),
      relPath: '../sub-other',
      interactor,
    })
    expect(result).toBe('Attempt to navigate outside the root folder')
  })
})
