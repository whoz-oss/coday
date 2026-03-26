import * as fsp from 'node:fs/promises'
import * as os from 'node:os'
import * as path from 'node:path'
import { sanitizeBranchName, isInsideWorktree, resolveWorktreeDirName } from './git-worktree.tools'

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

describe('resolveWorktreeDirName', () => {
  it('returns sanitized branch when no explicit projectName is given', () => {
    expect(resolveWorktreeDirName('coday', 'feat-my-feature', undefined)).toBe('feat-my-feature')
  })

  it('strips the parent prefix when explicit projectName matches convention', () => {
    expect(resolveWorktreeDirName('coday', 'feat-my-feature', 'coday__feat-my-feature')).toBe('feat-my-feature')
  })

  it('handles legacy projectName whose suffix differs from the sanitized branch', () => {
    // A worktree created manually with a different directory name than what the
    // current convention would derive from the branch name.
    const branch = 'feature/vincent.audibert/issue-634-manual-delegation-async'
    const sanitized = 'feature-vincent-audibert-issue-634-manual-delegation-async'
    const legacyProjectName = 'coday__feat-vincent-audibert-issue-0634-move-manual-delegation-to-async'
    expect(resolveWorktreeDirName('coday', sanitized, legacyProjectName)).toBe(
      'feat-vincent-audibert-issue-0634-move-manual-delegation-to-async'
    )
    // The variable `branch` is used only to document the scenario; sanitized is what matters.
    void branch
  })

  it('falls back to sanitized branch when explicit projectName does not start with parent prefix', () => {
    expect(resolveWorktreeDirName('coday', 'feat-my-feature', 'some-other-project')).toBe('feat-my-feature')
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
