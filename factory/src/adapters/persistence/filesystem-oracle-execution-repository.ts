import type { OracleExecutionRepository } from '../../ports/persistence/oracle-execution-repository.js'
import type { OracleDefinition } from '../../domain/oracle/oracle-definition.js'

/**
 * Filesystem oracle-definition repository adapter.
 *
 * Delegates to the injected definition registry that owns directory scanning,
 * path/identity validation and content hashing. The registry is injected as a
 * structural dependency so the adapter stays free of any `.mjs` import and can
 * be bundled into the operational artifact; `factory/lib/oracle-definition.mjs`
 * wires the concrete registry at the composition edge.
 */

export interface OracleDefinitionRegistryLike {
  initialize(): Promise<unknown>
  get(id: string): OracleDefinition | null
  list(): OracleDefinition[]
}

export class FilesystemOracleExecutionRepository implements OracleExecutionRepository {
  constructor(private readonly registry: OracleDefinitionRegistryLike) {}

  async list(): Promise<OracleDefinition[]> {
    return this.registry.list()
  }

  async get(id: string): Promise<OracleDefinition | null> {
    return this.registry.get(id)
  }
}

/** Wires a filesystem oracle-definition repository around a concrete registry. */
export function createFilesystemOracleExecutionRepository(
  registry: OracleDefinitionRegistryLike
): FilesystemOracleExecutionRepository {
  return new FilesystemOracleExecutionRepository(registry)
}
