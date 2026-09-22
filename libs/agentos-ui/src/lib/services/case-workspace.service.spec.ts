import { pullRequestIndicator, WorkspaceView } from './case-workspace.service'

describe('case pull request indicator', () => {
  const workspace: WorkspaceView = {
    equipped: true,
    recoveryRequired: false,
    status: 'READY',
    branchName: 'agent/chosen',
  }
  it('shows no icon without a known associated PR, even for a pushed or modified branch', () => {
    expect(pullRequestIndicator()).toBeNull()
    expect(pullRequestIndicator(workspace)).toBeNull()
    for (const branchState of ['DETACHED', 'LOCAL_ONLY', 'PUSHED', 'UNPUSHED_COMMITS']) {
      for (const prState of ['NONE', 'UNKNOWN']) {
        expect(
          pullRequestIndicator({ ...workspace, git: { branchState, prState, dirty: true, unpushedCommits: 2 } })
        ).toBeNull()
      }
    }
  })
  it('distinguishes draft, open, merged and closed PRs', () => {
    const icon = (prState: string) =>
      pullRequestIndicator({ ...workspace, git: { branchState: 'PUSHED', prState, prNumber: 42 } })
    expect(new Set(['DRAFT', 'OPEN', 'MERGED', 'CLOSED_UNMERGED'].map((s) => icon(s)?.icon)).size).toBe(4)
    expect(icon('MERGED')?.label).toBe('PR #42 — Merged')
  })
  it('keeps branch modifications out of the PR indicator', () => {
    const git = { branchState: 'PUSHED', prState: 'OPEN', prNumber: 42 }
    expect(
      pullRequestIndicator({
        ...workspace,
        git: { ...git, branchState: 'UNPUSHED_COMMITS', dirty: true, unpushedCommits: 2 },
      })
    ).toEqual(pullRequestIndicator({ ...workspace, git }))
  })
  it('hides unavailable or no longer associated PR status', () => {
    const git = { branchState: 'PUSHED', prState: 'OPEN' }
    expect(pullRequestIndicator({ ...workspace, git: { ...git, error: 'HTTP 401' } })).toBeNull()
    expect(pullRequestIndicator({ ...workspace, branchName: undefined, git })).toBeNull()
    expect(pullRequestIndicator({ ...workspace, equipped: false, git })).toBeNull()
  })
})
