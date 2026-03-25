import * as path from 'node:path'
import * as fsp from 'node:fs/promises'
import { AssistantToolFactory, CommandContext, CodayTool, FunctionTool, Interactor } from '@coday/model'
import { IntegrationService, ProjectService } from '@coday/service'
import { runBash } from '@coday/function'

/**
 * Sanitizes a branch name to a safe directory name.
 * Replaces '/' and any non-alphanumeric character (except '-') with '-'.
 * Collapses consecutive '-'.
 *
 * Examples:
 *   'feat/my-feature'   → 'feat-my-feature'
 *   'fix/login-bug'     → 'fix-login-bug'
 *   'feat/vincent/auth' → 'feat-vincent-auth'
 */
export function sanitizeBranchName(branch: string): string {
  return branch
    .replace(/[^a-zA-Z0-9-]/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
}

/**
 * Checks whether the current project is inside a git worktree
 * (i.e. .git is a file rather than a directory).
 */
export async function isInsideWorktree(projectRoot: string): Promise<boolean> {
  const gitPath = path.join(projectRoot, '.git')
  try {
    const stat = await fsp.lstat(gitPath)
    return stat.isFile()
  } catch {
    return false
  }
}

function deriveWorktreeProjectName(parentProjectName: string, sanitizedBranch: string): string {
  return `${parentProjectName}__${sanitizedBranch}`
}

export class GitWorktreeTools extends AssistantToolFactory {
  static readonly TYPE = 'GIT_WORKTREE' as const

  constructor(
    interactor: Interactor,
    private readonly integrationService: IntegrationService,
    instanceName: string,
    config?: any,
    private readonly projectService?: ProjectService
  ) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    if (!this.integrationService.hasIntegration('GIT_WORKTREE')) {
      return result
    }

    if (!this.projectService) {
      this.interactor.debug('[WORKTREE] ProjectService not available, worktree tools disabled')
      return result
    }
    const projectService = this.projectService

    const projectRoot = context.project.root
    const parentProjectName = context.project.name
    const worktreesRoot = path.dirname(projectRoot)
    const inWorktree = await isInsideWorktree(projectRoot)

    // list_worktrees — always available
    const listWorktreesTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: `${this.name}__list_worktrees`,
        description:
          'Lists all git worktrees for the current repository, with their Coday project registration status.',
        parameters: { type: 'object', properties: {} },
        parse: JSON.parse,
        function: async (): Promise<string> => {
          const raw = await runBash({
            command: 'git worktree list --porcelain',
            root: projectRoot,
            interactor: this.interactor,
          })

          const entries: { path: string; branch: string }[] = []
          for (const block of raw.split(/\n\n+/).filter(Boolean)) {
            const lines = block.trim().split('\n')
            const pathLine = lines.find((l) => l.startsWith('worktree '))
            const branchLine = lines.find((l) => l.startsWith('branch '))
            if (!pathLine) continue
            const wtPath = pathLine.replace('worktree ', '').trim()
            const branch = branchLine ? branchLine.replace('branch refs/heads/', '').trim() : '(detached)'
            entries.push({ path: wtPath, branch })
          }

          // git worktree list always puts the main worktree first
          const mainWorktreePath = entries[0]?.path

          return JSON.stringify(
            entries.map((e) => {
              const isMain = e.path === mainWorktreePath
              const sanitized = sanitizeBranchName(e.branch)
              const projectName = isMain ? parentProjectName : deriveWorktreeProjectName(parentProjectName, sanitized)
              return {
                branch: e.branch,
                path: e.path,
                projectName,
                isMain,
              }
            }),
            null,
            2
          )
        },
      },
    }
    result.push(listWorktreesTool)

    // create_worktree — disabled when already inside a worktree
    if (!inWorktree) {
      const createWorktreeTool: FunctionTool<{ branch: string }> = {
        type: 'function',
        function: {
          name: `${this.name}__create_worktree`,
          description:
            'Creates a git worktree on the given branch (creates the branch if it does not exist) and registers it as a Coday sub-project. Returns projectName, worktreePath and branch.',
          parameters: {
            type: 'object',
            properties: {
              branch: { type: 'string', description: 'Branch name to create or checkout (e.g., feat/my-feature)' },
            },
            ...({ required: ['branch'] } as object),
          },
          parse: JSON.parse,
          function: async ({ branch }: { branch: string }): Promise<string> => {
            if (!/^[a-zA-Z0-9._\/-]+$/.test(branch)) {
              return `Error: branch name contains invalid characters: ${branch}`
            }
            const sanitized = sanitizeBranchName(branch)
            const worktreePath = path.join(worktreesRoot, `${parentProjectName}__${sanitized}`)
            const projectName = deriveWorktreeProjectName(parentProjectName, sanitized)

            try {
              await fsp.access(worktreePath)
              return `Error: worktree path already exists: ${worktreePath}`
            } catch {
              /* doesn't exist, proceed */
            }

            // Try creating a new branch first; if it already exists, check it out instead.
            // Clean up any empty directory git may have created before failing so the fallback can succeed.
            let addResult = await runBash({
              command: `git worktree add "${worktreePath}" -b "${branch}"`,
              root: projectRoot,
              interactor: this.interactor,
            })
            if (addResult.startsWith('Command failed:')) {
              try {
                await fsp.rmdir(worktreePath)
              } catch {
                /* ignore */
              }
              addResult = await runBash({
                command: `git worktree add "${worktreePath}" "${branch}"`,
                root: projectRoot,
                interactor: this.interactor,
              })
              if (addResult.startsWith('Command failed:')) {
                return `Failed to create worktree:\n${addResult}`
              }
            }

            await projectService.registerWorktreeProject(projectName, worktreePath, parentProjectName)
            this.interactor.debug(`[WORKTREE] Registered project '${projectName}' at ${worktreePath}`)
            return JSON.stringify({ projectName, worktreePath, branch })
          },
        },
      }
      result.push(createWorktreeTool)
    }

    // remove_worktree — always available
    const removeWorktreeTool: FunctionTool<{ branch: string; force?: boolean }> = {
      type: 'function',
      function: {
        name: `${this.name}__remove_worktree`,
        description:
          'Removes a git worktree by branch name and cleans up the associated Coday project entry. Use force=true to remove even with uncommitted changes.',
        parameters: {
          type: 'object',
          properties: {
            branch: { type: 'string', description: 'Branch whose worktree to remove' },
            force: { type: 'boolean', description: 'Force removal even if the worktree has uncommitted changes' },
          },
          ...({ required: ['branch'] } as object),
        },
        parse: JSON.parse,
        function: async ({ branch, force }: { branch: string; force?: boolean }): Promise<string> => {
          if (!/^[a-zA-Z0-9._\/-]+$/.test(branch)) {
            return `Error: branch name contains invalid characters: ${branch}`
          }
          const sanitized = sanitizeBranchName(branch)
          const worktreePath = path.join(worktreesRoot, `${parentProjectName}__${sanitized}`)
          const projectName = deriveWorktreeProjectName(parentProjectName, sanitized)

          if (worktreePath === projectRoot) {
            return `Error: cannot remove the main worktree`
          }

          try {
            await fsp.access(worktreePath)
          } catch {
            return `Error: worktree path does not exist: ${worktreePath}`
          }

          const removeResult = await runBash({
            command: `git worktree remove "${worktreePath}"${force ? ' --force' : ''}`,
            root: projectRoot,
            interactor: this.interactor,
          })
          if (removeResult.startsWith('Command failed:')) {
            return `Failed to remove worktree:\n${removeResult}`
          }

          await runBash({ command: 'git worktree prune', root: projectRoot, interactor: this.interactor })
          await projectService.unregisterWorktreeProject(projectName)
          this.interactor.debug(`[WORKTREE] Unregistered project '${projectName}'`)
          return JSON.stringify({ removed: true, projectName, worktreePath, branch })
        },
      },
    }
    result.push(removeWorktreeTool)

    return result
  }
}
