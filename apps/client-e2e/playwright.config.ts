import { defineConfig, devices } from '@playwright/test'
import { nxE2EPreset } from '@nx/playwright/preset'
import { workspaceRoot } from '@nx/devkit'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

// The port scheme is read from its single source of truth (a data file, so no cross-project TS import and no ESM/CJS
// JSON import problem in this `type: module` package). No port literal here: dev base + role offset.
const scheme = JSON.parse(
  readFileSync(join(workspaceRoot, 'apps/server/src/lib/runtime/port-scheme.json'), 'utf8')
) as { offsets: Record<string, number>; profiles: Record<string, { base: number }> }
const devPort = (role: string): number => (scheme.profiles['dev']?.base ?? 0) + (scheme.offsets[role] ?? 0)

// The `webServer` below starts `nx run client:serve`, the Angular dev server ALONE (the specs mock the backend at the
// network layer), so the default baseURL is that dev server (dev base + client offset), not Express, which this config
// does not start: waiting for Express would time out unless the whole dev stack is already up. With the stack up
// (`pnpm web:serve:dev`), set BASE_URL to Express (dev base + server offset) to test through it like a user does.
const devClientUrl = `http://localhost:${devPort('client')}`

// For CI, you may want to set BASE_URL to the deployed application.
const baseURL = process.env['BASE_URL'] || devClientUrl

/**
 * Read environment variables from file.
 * https://github.com/motdotla/dotenv
 */
// require('dotenv').config();

/**
 * See https://playwright.dev/docs/test-configuration.
 */
export default defineConfig({
  ...nxE2EPreset(__filename, { testDir: './src' }),
  /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
  use: {
    baseURL,
    /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
    trace: 'on-first-retry',
  },
  /* Run your local dev server before starting the tests */
  webServer: {
    command: 'pnpm nx run client:serve',
    url: devClientUrl,
    reuseExistingServer: true,
    cwd: workspaceRoot,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },

    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },

    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },

    // Uncomment for mobile browsers support
    /* {
      name: 'Mobile Chrome',
      use: { ...devices['Pixel 5'] },
    },
    {
      name: 'Mobile Safari',
      use: { ...devices['iPhone 12'] },
    }, */

    // Uncomment for branded browsers
    /* {
      name: 'Microsoft Edge',
      use: { ...devices['Desktop Edge'], channel: 'msedge' },
    },
    {
      name: 'Google Chrome',
      use: { ...devices['Desktop Chrome'], channel: 'chrome' },
    } */
  ],
})
