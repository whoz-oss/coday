import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright config of the first-run wizard e2e: no dev server (the spec starts the `web:first-run` sandbox itself),
 * one browser, generous timeouts (JVM boot).
 */
export default defineConfig({
  testDir: './src',
  testMatch: /first-run-wizard\.spec\.ts$/,
  timeout: 10 * 60_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: { trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
