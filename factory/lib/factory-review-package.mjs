import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'

const hash = (value) => `sha256:${createHash('sha256').update(value).digest('hex')}`
export async function buildFrontendReviewPackage({ ticket, evidence, diff, claims, oracleResults, repoRoot }) {
  const artifacts = []
  for (const item of evidence.filter((entry) => entry.kind === 'artifact')) {
    if (isAbsolute(item.artifactRef)) throw new Error('REVIEW_ARTIFACT_OUT_OF_SCOPE')
    const absolute = resolve(repoRoot, item.artifactRef),
      rel = relative(repoRoot, absolute)
    if (rel === '..' || rel.startsWith('../') || isAbsolute(rel)) throw new Error('REVIEW_ARTIFACT_OUT_OF_SCOPE')
    const content = await readFile(absolute),
      observed = hash(content)
    if (observed !== item.artifactHash) throw new Error('REVIEW_ARTIFACT_HASH_MISMATCH')
    artifacts.push({ stepId: item.stepId, path: item.artifactRef, hash: item.artifactHash })
  }
  if (
    !Array.isArray(oracleResults) ||
    oracleResults.length === 0 ||
    oracleResults.some(
      (result) => result.outcome !== 'pass' || result.facts?.executed !== true || result.facts?.fromCache === true
    )
  )
    throw new Error('REVIEW_ORACLE_NOT_AUTHORITATIVE')
  if (!Array.isArray(diff) || !claims || !Array.isArray(claims.modifiedFiles)) throw new Error('REVIEW_PACKAGE_INVALID')
  return Object.freeze({ schemaVersion: '1', readOnly: true, ticket, artifacts, diff, claims, oracleResults })
}
