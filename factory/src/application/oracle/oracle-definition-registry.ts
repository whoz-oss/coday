/**
 * Filesystem adapter for the pure oracle-definition registry.
 *
 * The schema validation, canonical hashing and registry logic live in
 * `domain/oracle/oracle-definition.ts`; this module only wires Node `fs` to the
 * `OracleDefinitionSource` port and exposes the legacy-compatible
 * `OracleDefinitionRegistry(root)` constructor.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/oracle-definition.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 */

import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { OracleDefinitionRegistryCore, type OracleDefinitionSource } from '../../domain/oracle/oracle-definition.js'

/** Builds a `OracleDefinitionSource` rooted at `root` using Node `fs`. */
export function createFilesystemOracleDefinitionSource(root: string): OracleDefinitionSource {
  return {
    listFiles: () => readdir(root),
    readFile: (fileName) => readFile(join(root, fileName), 'utf8'),
  }
}

/**
 * Legacy-compatible registry: `new OracleDefinitionRegistry(root).initialize()`.
 * Delegates all validation and registry invariants to the pure domain core.
 */
export class OracleDefinitionRegistry extends OracleDefinitionRegistryCore {
  constructor(root: string) {
    super(createFilesystemOracleDefinitionSource(root))
  }
}
