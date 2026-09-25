import type { OracleDefinition } from '../../domain/oracle/oracle-definition.js'

/**
 * Persistence (read) port for the oracle-definition / oracle-execution context.
 *
 * Oracle definitions are immutable executable contracts loaded from a registry;
 * the port exposes the loaded catalogue and identity lookup, never the directory
 * layout or the validation rules.
 */

export interface OracleExecutionRepository {
  /** Every loaded oracle definition, in registry order. */
  list(): Promise<OracleDefinition[]>
  /** The definition identified by `id`, or `null` when absent. */
  get(id: string): Promise<OracleDefinition | null>
}
