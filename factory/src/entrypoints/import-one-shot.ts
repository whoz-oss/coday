/**
 * CLI entrypoint for the B4-T1 one-shot filesystem → PostgreSQL import.
 *
 * Usage:
 *   node --experimental-strip-types factory/src/entrypoints/import-one-shot.ts
 *   # or, once bundled/transpiled:
 *   node factory/lib/import-one-shot.mjs
 *
 * Environment:
 *   FACTORY_DATA_ROOT       source filesystem root (default `~/.coday/factory`)
 *   FACTORY_DEFINITIONS_ROOT override for the definition catalogue root
 *   FACTORY_ORACLES_ROOT     override for the oracle catalogue root
 *   DEFAULT_ORGANIZATION_ID / ORGANIZATION_ID  tenant (default `default`)
 *   DEFAULT_WORKSTREAM_ID   / WORKSTREAM_ID    workstream (default `default`)
 *   PGHOST / PGPORT / PGDATABASE / PGUSER / PGPASSWORD / PGSSL / PGPOOL_MAX
 *
 * Exit code: 0 when the verification report is `ok`, 1 otherwise. Discrepancies
 * are printed as one JSON line per context so an operator can pipe them out.
 */

import { homedir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { createPgPoolClient, resolveSqlDatabaseConfig } from '../adapters/persistence/sql/db.js'
import { runOneShotImport, type VerificationReport } from '../adapters/persistence/migration/one-shot-import.js'

/** Resolves the CLI configuration from the environment. */
export function resolveImportCliConfig(env: NodeJS.ProcessEnv = process.env): {
  dataRoot: string
  organizationId: string
  workstreamId: string
  definitionsRoot?: string
  oraclesRoot?: string
} {
  return {
    dataRoot: env.FACTORY_DATA_ROOT ?? join(homedir(), '.coday', 'factory'),
    organizationId: env.DEFAULT_ORGANIZATION_ID ?? env.ORGANIZATION_ID ?? 'default',
    workstreamId: env.DEFAULT_WORKSTREAM_ID ?? env.WORKSTREAM_ID ?? 'default',
    definitionsRoot: env.FACTORY_DEFINITIONS_ROOT,
    oraclesRoot: env.FACTORY_ORACLES_ROOT,
  }
}

/** Formats the human-readable summary of a verification report. */
export function formatVerificationReport(report: VerificationReport): string {
  const lines: string[] = []
  lines.push(
    `[one-shot-import] ok=${report.ok} aggregates=${report.totalFilesystemAggregates}/${report.totalSqlAggregates}`
  )
  for (const context of Object.values(report.contexts)) {
    lines.push(
      `[one-shot-import]   ${context.context}: filesystem=${context.filesystemCount} sql=${context.sqlCount} ok=${context.ok} discrepancies=${context.discrepancies.length}`
    )
    for (const discrepancy of context.discrepancies) lines.push(`[one-shot-import]     ${JSON.stringify(discrepancy)}`)
  }
  return lines.join('\n')
}

/** Runs the import against a live PostgreSQL pool and returns the report. */
export async function main(env: NodeJS.ProcessEnv = process.env): Promise<VerificationReport> {
  const config = resolveImportCliConfig(env)
  const sqlClient = await createPgPoolClient(resolveSqlDatabaseConfig(env))
  return runOneShotImport({
    dataRoot: config.dataRoot,
    organizationId: config.organizationId,
    workstreamId: config.workstreamId,
    definitionsRoot: config.definitionsRoot,
    oraclesRoot: config.oraclesRoot,
    sqlClient,
  })
}

const invokedDirectly = process.argv[1] !== undefined && fileURLToPath(import.meta.url) === process.argv[1]

if (invokedDirectly) {
  main()
    .then((report) => {
      console.log(formatVerificationReport(report))
      process.exitCode = report.ok ? 0 : 1
    })
    .catch((error) => {
      console.error(`[one-shot-import] failed: ${error?.stack ?? error}`)
      process.exitCode = 1
    })
}
