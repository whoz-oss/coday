import { Interactor } from '@coday/model'
import { runBash } from '@coday/function'

const dangerKeywords = ['push', 'push -f', 'push --force', 'push --tags', 'reset', '&&', 'clean']

/**
 * Worktree mutating subcommands are blocked: use the dedicated GIT_WORKTREE tools instead.
 * Read-only worktree commands (list, lock, unlock, repair) are still allowed.
 */
const blockedWorktreeSubcommands = ['add', 'move', 'remove', 'prune']

export const git = async ({
  params,
  root,
  interactor,
}: {
  params: string
  root: string
  interactor: Interactor
}): Promise<string> => {
  const trimmed = params.trimStart()
  if (trimmed.startsWith('worktree ')) {
    const subcommand = trimmed.slice('worktree '.length).trimStart().split(/\s+/)[0] ?? ''
    if (blockedWorktreeSubcommands.includes(subcommand)) {
      return `Error: 'git worktree ${subcommand}' is not allowed via the generic git tool. Use the dedicated worktree tools (list_worktrees, create_worktree, remove_worktree) instead.`
    }
  }

  const command = `git ${params}`
  const requireConfirmation = dangerKeywords.some((danger) => params.includes(danger))
  return await runBash({
    command,
    root,
    interactor,
    requireConfirmation,
  })
}
