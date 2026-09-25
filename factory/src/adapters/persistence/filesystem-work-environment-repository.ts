import type { WorkEnvironmentRepository } from '../../ports/persistence/work-environment-repository.js'
import type {
  ValidationFailure,
  WorkUnitEnvironment,
  WorkUnitEnvironmentState,
} from '../../domain/environment/work-unit-environment.js'
import type { EnvironmentSnapshot, StoreWriteResult, WorkUnitEnvironmentPaths } from './work-unit-environment-store.js'

/**
 * Filesystem work-environment repository adapter.
 *
 * Environment locking, crash recovery and the on-disk format live in the
 * injected environment store; this adapter expresses them through the port.
 * The store is injected as a structural dependency so the adapter stays free of
 * any `.mjs` import and can be bundled into the operational artifact;
 * `factory/lib/work-unit-environment-store.mjs` wires the concrete store at the
 * composition edge.
 */
export interface WorkUnitEnvironmentStoreLike {
  paths(namespaceId: string, environmentId: string): WorkUnitEnvironmentPaths
  read(namespaceId: string, environmentId: string): Promise<EnvironmentSnapshot | null>
  list(namespaceId: string, filter?: { states?: readonly WorkUnitEnvironmentState[] }): Promise<EnvironmentSnapshot[]>
  reserve(environment: unknown): Promise<StoreWriteResult | ValidationFailure>
  transition(
    namespaceId: string,
    environmentId: string,
    next: WorkUnitEnvironment,
    options?: { expectedRevision?: number; errorCode?: string }
  ): Promise<StoreWriteResult | ValidationFailure>
}

export class FilesystemWorkEnvironmentRepository implements WorkEnvironmentRepository {
  constructor(private readonly store: WorkUnitEnvironmentStoreLike) {}

  paths(namespaceId: string, environmentId: string): WorkUnitEnvironmentPaths {
    return this.store.paths(namespaceId, environmentId)
  }

  read(namespaceId: string, environmentId: string): Promise<EnvironmentSnapshot | null> {
    return this.store.read(namespaceId, environmentId)
  }

  list(namespaceId: string, filter?: { states?: readonly WorkUnitEnvironmentState[] }): Promise<EnvironmentSnapshot[]> {
    return this.store.list(namespaceId, filter)
  }

  reserve(environment: unknown): Promise<StoreWriteResult | ValidationFailure> {
    return this.store.reserve(environment)
  }

  transition(
    namespaceId: string,
    environmentId: string,
    next: WorkUnitEnvironment,
    options?: { expectedRevision?: number; errorCode?: string }
  ): Promise<StoreWriteResult | ValidationFailure> {
    return this.store.transition(namespaceId, environmentId, next, options)
  }
}

/** Wires a filesystem work-environment repository around a concrete store. */
export function createFilesystemWorkEnvironmentRepository(
  store: WorkUnitEnvironmentStoreLike
): FilesystemWorkEnvironmentRepository {
  return new FilesystemWorkEnvironmentRepository(store)
}
