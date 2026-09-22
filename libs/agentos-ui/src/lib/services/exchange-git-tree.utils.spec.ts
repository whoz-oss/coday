import { ExchangeDiffFileStatusEnum } from '@whoz-oss/agentos-api-client'
import { DiffFile, GitFileStatus } from './exchange-environment.service'
import { deletedFilesInDirectory, indexGitChanges } from './exchange-git-tree.utils'

const change = (path: string, status: GitFileStatus): DiffFile => ({
  path,
  status,
  additions: 0,
  deletions: 0,
  untracked: status === ExchangeDiffFileStatusEnum.UNTRACKED,
})

describe('Git decorations in the Exchange tree', () => {
  it('marks repository ancestors without decorating same-named documents or adjacent folders', () => {
    const tree = indexGitChanges([
      change('src/a.ts', ExchangeDiffFileStatusEnum.MODIFIED),
      change('src/deep/b.ts', ExchangeDiffFileStatusEnum.UNTRACKED),
    ])
    expect(tree.files.get('repo/src/a.ts')?.status).toBe(ExchangeDiffFileStatusEnum.MODIFIED)
    expect(tree.files.has('src/a.ts')).toBe(false)
    expect(tree.folders.get('repo')).toBe(2)
    expect(tree.folders.get('repo/src')).toBe(2)
    expect(tree.folders.get('repo/src/deep')).toBe(1)
    expect(tree.folders.has('repo/src-other')).toBe(false)
    expect(tree.folders.has('')).toBe(false)
  })

  it('keeps deleted files reachable when their directory has vanished, without duplicating live entries', () => {
    const files = [
      change('gone.ts', ExchangeDiffFileStatusEnum.DELETED),
      change('old/deep/a.ts', ExchangeDiffFileStatusEnum.DELETED),
      change('src/b.ts', ExchangeDiffFileStatusEnum.DELETED),
    ]
    expect(deletedFilesInDirectory(files, 'repo', ['repo/gone.ts'], ['repo/src'])).toEqual([
      { path: 'repo/old/deep/a.ts', name: 'old/deep/a.ts', status: ExchangeDiffFileStatusEnum.DELETED },
    ])
    expect(deletedFilesInDirectory(files, 'repo/src', [], [])).toEqual([
      { path: 'repo/src/b.ts', name: 'b.ts', status: ExchangeDiffFileStatusEnum.DELETED },
    ])
    expect(deletedFilesInDirectory(files, 'repo/s', [], [])).toEqual([])
  })

  it('keeps deletions out of document folders and clears decorations when no changes remain', () => {
    const files = [change('a.ts', ExchangeDiffFileStatusEnum.DELETED)]
    for (const directory of ['', 'docs', 'repository']) {
      expect(deletedFilesInDirectory(files, directory, [], [])).toEqual([])
    }
    const tree = indexGitChanges([])
    expect(tree.files.size).toBe(0)
    expect(tree.folders.size).toBe(0)
    expect(deletedFilesInDirectory([], 'repo', [], [])).toEqual([])
  })
})
