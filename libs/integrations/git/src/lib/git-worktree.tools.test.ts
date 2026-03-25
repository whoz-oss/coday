import * as fsp from 'node:fs/promises'
import * as os from 'node:os'
import * as path from 'node:path'
import { sanitizeBranchName, isInsideWorktree } from './git-worktree.tools'

describe('sanitizeBranchName', () => {
  it('replaces / with -', () => {
    expect(sanitizeBranchName('feat/my-feature')).toBe('feat-my-feature')
  })

  it('handles fix/ prefix', () => {
    expect(sanitizeBranchName('fix/login-bug')).toBe('fix-login-bug')
  })

  it('handles multiple path segments', () => {
    expect(sanitizeBranchName('feat/vincent/auth')).toBe('feat-vincent-auth')
  })

  it('leaves a simple name unchanged', () => {
    expect(sanitizeBranchName('simple')).toBe('simple')
  })

  it('collapses consecutive dashes', () => {
    expect(sanitizeBranchName('a--b')).toBe('a-b')
  })

  it('strips a leading dash', () => {
    expect(sanitizeBranchName('-leading')).toBe('leading')
  })

  it('strips a trailing dash', () => {
    expect(sanitizeBranchName('trailing-')).toBe('trailing')
  })
})

describe('isInsideWorktree', () => {
  let tmpDir: string

  beforeEach(async () => {
    tmpDir = await fsp.mkdtemp(path.join(os.tmpdir(), 'coday-worktree-test-'))
  })

  afterEach(async () => {
    await fsp.rm(tmpDir, { recursive: true, force: true })
  })

  it('returns false when .git does not exist', async () => {
    expect(await isInsideWorktree(tmpDir)).toBe(false)
  })

  it('returns true when .git is a file', async () => {
    await fsp.writeFile(path.join(tmpDir, '.git'), 'gitdir: ../main/.git/worktrees/feat')
    expect(await isInsideWorktree(tmpDir)).toBe(true)
  })

  it('returns false when .git is a directory', async () => {
    await fsp.mkdir(path.join(tmpDir, '.git'))
    expect(await isInsideWorktree(tmpDir)).toBe(false)
  })
})
