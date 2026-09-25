/**
 * Adapter-facing re-export of the common storage kernel.
 *
 * Adapters import kernel primitives from here so their dependency is expressed
 * against the persistence adapter layer rather than reaching into
 * `infrastructure` directly.
 */
export * from '../../infrastructure/storage/storage-kernel.js'
