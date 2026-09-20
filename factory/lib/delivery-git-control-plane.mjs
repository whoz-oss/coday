import { createHash } from 'node:crypto'
import { realpath } from 'node:fs/promises'
import { isAbsolute } from 'node:path'
import { createExecFileRunner } from './git-worktree.mjs'

const SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i
const SAFE_REMOTE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
// Branch names used in refspecs must not contain characters that git interprets specially.
// Colon (:) splits refspecs, space terminates args, NUL is a protocol delimiter.
const SAFE_BRANCH = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,255}$/
const fail = (code, details = {}) => { throw Object.assign(new Error(code), { code, details }) }

// Parse `git status --porcelain=v1 -z` NUL-delimited output.
// Each entry is: XY SP <path> NUL [original NUL for renames]
// The -z format avoids quoting issues with special characters and spaces in paths.
function parseStatusZ(raw) {
  const results = []
  let i = 0
  while (i < raw.length) {
    if (raw.length - i < 4) break // need at least "XY " + one char + NUL
    const xy = raw.slice(i, i + 2)
    if (raw[i + 2] !== ' ') { i++; continue } // malformed, skip
    const nulPos = raw.indexOf('\0', i + 3)
    if (nulPos === -1) break
    const path = raw.slice(i + 3, nulPos)
    i = nulPos + 1
    // Renames/copies: second NUL-terminated entry is the original path
    let originalPath = null
    if (xy[0] === 'R' || xy[0] === 'C' || xy[1] === 'R' || xy[1] === 'C') {
      const nulPos2 = raw.indexOf('\0', i)
      if (nulPos2 !== -1) { originalPath = raw.slice(i, nulPos2); i = nulPos2 + 1 }
    }
    results.push({ code: xy, path, originalPath })
  }
  return results
}

function safePath(path) { return typeof path === 'string' && path.length > 0 && !isAbsolute(path) && !path.split(/[\\/]/).includes('..') && !path.includes('\0') }
const matchesPrefix = (path, prefix) => path === prefix || path.startsWith(`${prefix}/`)

export class DeliveryGitControlPlane {
  constructor({ runner = createExecFileRunner(), serviceIdentity, configuredRemote, allowedPaths = [], protectedPaths = [] }) {
    if (!serviceIdentity?.name || !serviceIdentity?.email) fail('SERVICE_IDENTITY_NOT_CONFIGURED')
    if (configuredRemote !== null && configuredRemote !== undefined && !SAFE_REMOTE.test(configuredRemote)) fail('INVALID_REMOTE_CONFIGURATION')
    if (![...allowedPaths, ...protectedPaths].every(safePath)) fail('INVALID_PATH_POLICY')
    this.runner = runner; this.identity = serviceIdentity; this.remote = configuredRemote; this.allowedPaths = allowedPaths; this.protectedPaths = protectedPaths
  }
  async _run(args, cwd) { return this.runner('git', args, { cwd }) }
  async inspect(binding) {
    const canonical = await realpath(binding.worktreePath).catch(() => fail('CANONICAL_WORKTREE_REQUIRED'))
    if (canonical !== binding.worktreePath) fail('CANONICAL_WORKTREE_REQUIRED')
    const top = await this._run(['rev-parse', '--show-toplevel'], canonical)
    if (top.exitCode || await realpath(top.stdout.trim()).catch(() => null) !== canonical) fail('CANONICAL_WORKTREE_REQUIRED')
    // Validate branch name against safe pattern before using it in any git argument.
    if (!SAFE_BRANCH.test(binding.branch ?? '')) fail('INVALID_BRANCH_NAME')
    const branch = await this._run(['branch', '--show-current'], canonical), head = await this._run(['rev-parse', 'HEAD'], canonical)
    if (branch.exitCode || branch.stdout.trim() !== binding.branch || head.exitCode || !SHA.test(head.stdout.trim())) fail('WORKTREE_BINDING_UNCERTAIN')
    if (head.stdout.trim() !== binding.expectedHead) fail('STALE_HEAD', { expected: binding.expectedHead, actual: head.stdout.trim() })
    // Use NUL-delimited format (-z) for reliable parsing of paths with special characters and spaces.
    const status = await this._run(['status', '--porcelain=v1', '-z', '--untracked-files=all'], canonical)
    if (status.exitCode) fail('GIT_INSPECTION_FAILED')
    const files = parseStatusZ(status.stdout)
    // Check scope and protected files on BOTH old and new paths for renames.
    const protectedHit = files.find((item) =>
      this.protectedPaths.some((prefix) => matchesPrefix(item.path, prefix) || (item.originalPath && matchesPrefix(item.originalPath, prefix)))
    )
    if (protectedHit) fail('PROTECTED_FILE_CHANGED', { path: protectedHit.path })
    const outOfScope = files.find((item) =>
      !this.allowedPaths.some((prefix) => matchesPrefix(item.path, prefix)) ||
      (item.originalPath && !this.allowedPaths.some((prefix) => matchesPrefix(item.originalPath, prefix)))
    )
    if (outOfScope) fail('SCOPE_VIOLATION', { path: outOfScope.path })
    const allPaths = [...new Set(files.flatMap((item) => item.originalPath ? [item.path, item.originalPath] : [item.path]))].sort()
    const trackedDiff = await this._run(['diff', '--binary', '--no-ext-diff', binding.baseCommit, '--', ...allPaths], canonical)
    if (trackedDiff.exitCode) fail('GIT_INSPECTION_FAILED')
    const untracked = files.filter((item) => item.code === '??').map((item) => item.path).sort()
    const diffContent = `${trackedDiff.stdout}\n${untracked.map((path) => `untracked ${path}`).join('\n')}`
    return { worktreePath: canonical, branch: binding.branch, headCommit: head.stdout.trim(), files, diffHash: `sha256:${createHash('sha256').update(diffContent).digest('hex')}` }
  }
  compareClaims(inspection, claims) {
    if (!claims || Object.keys(claims).some((key) => !['paths', 'diffHash'].includes(key)) || !Array.isArray(claims.paths)) fail('INVALID_CLAIMS')
    const actual = [...new Set(inspection.files.map((item) => item.path))].sort(), claimed = [...new Set(claims.paths)].sort()
    if (JSON.stringify(actual) !== JSON.stringify(claimed) || claims.diffHash !== inspection.diffHash) fail('CLAIMS_MISMATCH')
    return { ok: true }
  }
  async checkpoint(binding, { message, claims }) {
    const inspection = await this.inspect(binding); this.compareClaims(inspection, claims)
    if (inspection.files.length === 0) return { changed: false, commit: inspection.headCommit, inspection }
    const paths = inspection.files.map((item) => item.path)
    const add = await this._run(['add', '--', ...paths], inspection.worktreePath); if (add.exitCode) fail('GIT_STAGE_FAILED')
    const staged = await this._run(['diff', '--cached', '--quiet', '--exit-code'], inspection.worktreePath)
    if (staged.exitCode === 0) return { changed: false, commit: inspection.headCommit, inspection }
    if (staged.exitCode !== 1) fail('GIT_STAGE_INDETERMINATE')
    const commit = await this._run(['-c', `user.name=${this.identity.name}`, '-c', `user.email=${this.identity.email}`, 'commit', '--no-gpg-sign', '-m', message, '--', ...paths], inspection.worktreePath)
    if (commit.exitCode) fail('GIT_COMMIT_FAILED')
    const head = await this._run(['rev-parse', 'HEAD'], inspection.worktreePath), identity = await this._run(['show', '-s', '--format=%cn%n%ce', 'HEAD'], inspection.worktreePath)
    if (head.exitCode || !SHA.test(head.stdout.trim()) || identity.stdout.trim() !== `${this.identity.name}\n${this.identity.email}`) fail('GIT_COMMIT_INDETERMINATE')
    return { changed: true, commit: head.stdout.trim(), previousHead: inspection.headCommit, inspection }
  }
  async push(binding) {
    if (!this.remote) return { ok: false, blocked: true, error: { code: 'REMOTE_NOT_CONFIGURED' } }
    const inspection = await this.inspect(binding), remoteHeadBefore = await this._run(['ls-remote', '--heads', this.remote, `refs/heads/${binding.branch}`], inspection.worktreePath)
    if (remoteHeadBefore.exitCode) fail('REMOTE_INSPECTION_FAILED')
    const previous = remoteHeadBefore.stdout.trim().split(/\s+/)[0] || null
    // Idempotent: if the remote already points to our commit, no push needed.
    if (previous === inspection.headCommit) return { ok: true, changed: false, headCommit: inspection.headCommit }
    const push = await this._run(['push', '--porcelain', this.remote, `refs/heads/${binding.branch}:refs/heads/${binding.branch}`], inspection.worktreePath)
    if (push.exitCode) fail('GIT_PUSH_FAILED')
    const remoteHeadAfter = await this._run(['ls-remote', '--heads', this.remote, `refs/heads/${binding.branch}`], inspection.worktreePath), actual = remoteHeadAfter.stdout.trim().split(/\s+/)[0]
    if (remoteHeadAfter.exitCode || actual !== inspection.headCommit) fail('GIT_PUSH_INDETERMINATE')
    return { ok: true, changed: true, headCommit: inspection.headCommit, previousRemoteHead: previous }
  }
}
