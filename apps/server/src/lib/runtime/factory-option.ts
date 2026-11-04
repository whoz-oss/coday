/**
 * `CODAY_NO_FACTORY=1` leaves the managed Factory out: Coday neither downloads nor starts it, and does not proxy
 * `/api/factory` to a managed port. The Factory stays the default without it.
 *
 * It only concerns the managed Factory: an external one (`FACTORY_URL`) is still proxied, since the user asked for it.
 */
export const isManagedFactoryDisabled = (env: NodeJS.ProcessEnv = process.env): boolean =>
  env['CODAY_NO_FACTORY'] === '1' || env['CODAY_NO_FACTORY'] === 'true'
