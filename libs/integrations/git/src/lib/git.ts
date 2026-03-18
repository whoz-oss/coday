import { Interactor } from '@coday/model'
import { runBash } from '@coday/function'

const dangerKeywords = ['push', 'push -f', 'push --force', 'push --tags', 'reset', '&&', 'clean']

/**
 * All worktree subcommands are blocked except 'list'.
 * Use the dedicated GIT_WORKTREE tools (list_worktrees, create_worktree, remove_worktree) instead.
 */
const allowedWorktreeSubcommands = ['list']

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
    if (!allowedWorktreeSubcommands.includes(subcommand)) {
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
