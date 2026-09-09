import { log } from './logger'

const CODAY_WEB_PACKAGE = '@whoz-oss/coday-web'

/**
 * Minimal structural type for the Electron App object — avoids a direct
 * electron import in the library (consistent with the pattern used in logger.ts).
 */
interface AppLike {
  isPackaged: boolean
  getVersion(): string
}

/**
 * Resolve the npm package spec for the Coday web server.
 *
 * Resolution order:
 * 1. `CODAY_WEB_VERSION` env var (non-empty) → `@whoz-oss/coday-web@<value>`
 *    Accepts an exact version (`0.240.0`) or a dist-tag (`latest`, `next`).
 *    This is the developer / support escape hatch.
 * 2. `app.isPackaged === true` → `@whoz-oss/coday-web@<app.getVersion()>`
 *    Pins the packaged desktop app to the exact npm version it was released with.
 * 3. Otherwise (dev / unpackaged run) → `@whoz-oss/coday-web`
 *    Preserves today's behaviour so `nx run desktop:serve` keeps working.
 *    When unpackaged, `app.getVersion()` returns Electron's own version
 *    (e.g. `38.3.0`), which is valid semver but completely wrong, so
 *    `isPackaged` is the essential guard for case 2.
 *
 * @param app - Electron App instance (passed to avoid a direct electron import)
 * @returns The fully-qualified npm package spec to pass to npx
 */
export function resolveCodayWebSpec(app: AppLike): string {
  const envVersion = process.env['CODAY_WEB_VERSION']
  if (envVersion) {
    const spec = `${CODAY_WEB_PACKAGE}@${envVersion}`
    log('INFO', `resolveCodayWebSpec: using CODAY_WEB_VERSION env var → ${spec}`)
    return spec
  }

  if (app.isPackaged) {
    const appVersion = app.getVersion()
    const spec = `${CODAY_WEB_PACKAGE}@${appVersion}`
    log('INFO', `resolveCodayWebSpec: packaged app, pinning to app version → ${spec}`)
    return spec
  }

  log('INFO', `resolveCodayWebSpec: unpackaged / dev run, using unpinned → ${CODAY_WEB_PACKAGE}`)
  return CODAY_WEB_PACKAGE
}
