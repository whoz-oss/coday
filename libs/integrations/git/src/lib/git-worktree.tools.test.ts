import * as path from 'node:path'
import * as os from 'node:os'
import { sanitizeBranchName, isInsideWorktree, resolveWorktreeDirName, GitWorktreeTools } from './git-worktree.tools'
import { runBash } from '@coday/function'
import { CommandContext, Interactor } from '@coday/model'
import { IntegrationService, ProjectService } from '@coday/service'

// Mock @coday/function so runBash is fully controllable in all tests
jest.mock('@coday/function', () => ({ runBash: jest.fn() }))

// Mock node:fs/promises at module level (jest.mock is hoisted before imports).
// We keep the real implementations for all methods except lstat and access,
// which need to be jest.fn() so we can override them per-test.
// The isInsideWorktree describe block restores real behaviour via mockImplementation.
const realFsp = jest.requireActual<typeof import('node:fs/promises')>('node:fs/promises')
jest.mock('node:fs/promises', () => ({
  ...jest.requireActual<typeof import('node:fs/promises')>('node:fs/promises'),
  lstat: jest.fn(),
  access: jest.fn(),
}))

import * as fsp from 'node:fs/promises'

const mockRunBash = runBash as jest.MockedFunction<typeof runBash>
const mockLstat = fsp.lstat as jest.MockedFunction<typeof fsp.lstat>
const mockAccess = fsp.access as jest.MockedFunction<typeof fsp.access>

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
  it('strips the parent prefix when projectName matches convention', () => {
    expect(resolveWorktreeDirName('coday', 'coday__feat-my-feature')).toBe('feat-my-feature')
  })

  it('handles legacy projectName whose suffix differs from the sanitized branch', () => {
    const legacyProjectName = 'coday__feat-vincent-audibert-issue-0634-move-manual-delegation-to-async'
    expect(resolveWorktreeDirName('coday', legacyProjectName)).toBe(
      'feat-vincent-audibert-issue-0634-move-manual-delegation-to-async'
    )
  })

  it('returns null when projectName does not start with parent prefix', () => {
    expect(resolveWorktreeDirName('coday', 'some-other-project')).toBeNull()
  })

  it('returns null when projectName equals the parent project name without suffix', () => {
    expect(resolveWorktreeDirName('coday', 'coday')).toBeNull()
  })
})

describe('isInsideWorktree', () => {
  let tmpDir: string

  beforeEach(async () => {
    // Delegate lstat to the real implementation for this describe block
    mockLstat.mockImplementation((...args) => realFsp.lstat(...(args as Parameters<typeof realFsp.lstat>)))
    tmpDir = await realFsp.mkdtemp(path.join(os.tmpdir(), 'coday-worktree-test-'))
  })

  afterEach(async () => {
    await realFsp.rm(tmpDir, { recursive: true, force: true })
    mockLstat.mockReset()
  })

  it('returns false when .git does not exist', async () => {
    expect(await isInsideWorktree(tmpDir)).toBe(false)
  })

  it('returns true when .git is a file', async () => {
    await realFsp.writeFile(path.join(tmpDir, '.git'), 'gitdir: ../main/.git/worktrees/feat')
    expect(await isInsideWorktree(tmpDir)).toBe(true)
  })

  it('returns false when .git is a directory', async () => {
    await realFsp.mkdir(path.join(tmpDir, '.git'))
    expect(await isInsideWorktree(tmpDir)).toBe(false)
  })
})

describe('GitWorktreeTools - create_worktree postCreateScript', () => {
  const PROJECT_ROOT = '/projects/myproject'
  const WORKTREES_ROOT = '/projects'
  const PROJECT_NAME = 'myproject'

  let interactor: Interactor
  let integrationService: IntegrationService
  let projectService: ProjectService

  beforeEach(() => {
    mockRunBash.mockReset()
    mockLstat.mockReset()
    mockAccess.mockReset()

    interactor = {
      debugLevelEnabled: false,
      debug: jest.fn(),
      warn: jest.fn(),
      error: jest.fn(),
      displayText: jest.fn(),
      thinking: jest.fn(),
      sendEvent: jest.fn(),
      kill: jest.fn(),
    } as unknown as Interactor

    integrationService = {
      hasIntegration: jest.fn().mockReturnValue(true),
    } as unknown as IntegrationService

    projectService = {
      registerWorktreeProject: jest.fn().mockResolvedValue(undefined),
      unregisterWorktreeProject: jest.fn().mockResolvedValue(undefined),
    } as unknown as ProjectService
  })

  async function getCreateWorktreeTool(config?: any) {
    // isInsideWorktree checks fsp.lstat('.git') — return a stat where isFile() is false
    // so the create_worktree tool is included in the built tools
    mockLstat.mockResolvedValue({ isFile: () => false } as any)

    const tools = new GitWorktreeTools(interactor, integrationService, 'GIT_WORKTREE', config, projectService)
    const context = new CommandContext({ root: PROJECT_ROOT, name: PROJECT_NAME, description: '' } as any, 'testuser')
    const builtTools = await tools.getTools(context, [], 'test-agent')
    return builtTools.find((t) => t.function.name === 'GIT_WORKTREE__create_worktree')!
  }

  it('returns basic response when no postCreateScript is configured', async () => {
    const tool = await getCreateWorktreeTool({})

    // fsp.access(worktreePath) — must throw so code proceeds (path doesn't exist yet)
    mockAccess.mockRejectedValueOnce(new Error('ENOENT'))
    // runBash for git worktree add — success
    mockRunBash.mockResolvedValueOnce('Output: worktree created')

    const raw = (await tool.function.function({ branch: 'feat/test-1' })) as string
    const result = JSON.parse(raw)

    expect(result.projectName).toBe('myproject__feat-test-1')
    expect(result.worktreePath).toBe(path.join(WORKTREES_ROOT, 'myproject__feat-test-1'))
    expect(result.branch).toBe('feat/test-1')
    expect(result.warning).toBeUndefined()
    expect(result.postCreateScriptOutput).toBeUndefined()
    expect(interactor.warn).not.toHaveBeenCalled()
    expect(interactor.displayText).not.toHaveBeenCalled()
  })

  it('runs postCreateScript and includes output on success', async () => {
    const tool = await getCreateWorktreeTool({ postCreateScript: './init.sh' })

    // fsp.access(worktreePath) — throws (path doesn't exist)
    mockAccess.mockRejectedValueOnce(new Error('ENOENT'))
    // runBash for git worktree add — success
    mockRunBash.mockResolvedValueOnce('Output: worktree created')
    // fsp.access(scriptPath, X_OK) — script exists and is executable
    mockAccess.mockResolvedValueOnce(undefined)
    // runBash for script execution — success
    mockRunBash.mockResolvedValueOnce('hello from script')

    const raw = (await tool.function.function({ branch: 'feat/test-2' })) as string
    const result = JSON.parse(raw)

    expect(result.postCreateScriptOutput).toContain('hello from script')
    expect(result.warning).toBeUndefined()
    expect(interactor.displayText).toHaveBeenCalledWith(expect.stringContaining('postCreateScript completed'))
    expect(interactor.warn).not.toHaveBeenCalled()
  })

  it('warns when postCreateScript is not found or not executable', async () => {
    const tool = await getCreateWorktreeTool({ postCreateScript: './missing.sh' })

    // fsp.access(worktreePath) — throws (path doesn't exist)
    mockAccess.mockRejectedValueOnce(new Error('ENOENT'))
    // runBash for git worktree add — success
    mockRunBash.mockResolvedValueOnce('Output: worktree created')
    // fsp.access(scriptPath, X_OK) — script not found
    mockAccess.mockRejectedValueOnce(new Error('ENOENT'))

    const raw = (await tool.function.function({ branch: 'feat/test-3' })) as string
    const result = JSON.parse(raw)

    expect(result.warning).toContain('not found or not executable')
    expect(result.postCreateScriptOutput).toBeUndefined()
    expect(interactor.warn).toHaveBeenCalled()
    expect(interactor.displayText).not.toHaveBeenCalled()
    // runBash called only once for git worktree add, not for the script
    expect(mockRunBash).toHaveBeenCalledTimes(1)
  })

  it('warns when postCreateScript execution fails', async () => {
    const tool = await getCreateWorktreeTool({ postCreateScript: './failing.sh' })

    // fsp.access(worktreePath) — throws (path doesn't exist)
    mockAccess.mockRejectedValueOnce(new Error('ENOENT'))
    // runBash for git worktree add — success
    mockRunBash.mockResolvedValueOnce('Output: worktree created')
    // fsp.access(scriptPath, X_OK) — script exists
    mockAccess.mockResolvedValueOnce(undefined)
    // runBash for script — fails
    mockRunBash.mockResolvedValueOnce('Command failed: exit code 1')

    const raw = (await tool.function.function({ branch: 'feat/test-4' })) as string
    const result = JSON.parse(raw)

    expect(result.warning).toContain('postCreateScript failed')
    expect(result.postCreateScriptOutput).toBeUndefined()
    expect(interactor.warn).toHaveBeenCalled()
    expect(interactor.displayText).not.toHaveBeenCalled()
  })

  it('executes postCreateScript with worktree path as working directory', async () => {
    const tool = await getCreateWorktreeTool({ postCreateScript: './init.sh' })

    // fsp.access(worktreePath) — throws (path doesn't exist)
    mockAccess.mockRejectedValueOnce(new Error('ENOENT'))
    // runBash for git worktree add — success
    mockRunBash.mockResolvedValueOnce('Output: worktree created')
    // fsp.access(scriptPath, X_OK) — script exists
    mockAccess.mockResolvedValueOnce(undefined)
    // runBash for script — success
    mockRunBash.mockResolvedValueOnce('Output: done')

    await tool.function.function({ branch: 'feat/test-5' })

    const expectedWorktreePath = path.join(WORKTREES_ROOT, 'myproject__feat-test-5')
    const expectedScriptPath = path.resolve(PROJECT_ROOT, './init.sh')

    // The second runBash call (index 1) must use the worktree path as root
    const scriptCall = mockRunBash.mock.calls[1]!
    expect(scriptCall[0].root).toBe(expectedWorktreePath)
    expect(scriptCall[0].command).toBe(`"${expectedScriptPath}"`)
  })
})
