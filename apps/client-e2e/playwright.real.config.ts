import { defineConfig, devices } from '@playwright/test'

// The fixture starts and verifies its own instance; no Playwright webServer is reused.
export default defineConfig({
  testDir: './src',
  testMatch: /case-files-filter\.real\.spec\.ts$/,
  timeout: 10 * 60_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['line']],
  use: { trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
