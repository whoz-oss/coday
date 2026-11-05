import { expect, test } from '@playwright/test'
import { ChildProcess, execFileSync, spawn } from 'child_process'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'

/**
 * E2E of the first-run wizard against a REAL AgentOS (no LLM call: the API keys are dummies).
 *
 * The stack is the `web:local` script in `--managed --fresh` mode, on its own throwaway root (packed npm packages
 * installed in a temp dir, locally built JARs, fresh configDir): it never touches `~/.coday-local`.
 * Run it with:  pnpm nx run client-e2e:first-run-e2e
 *
 * SKIPPED (with a reason) when Java 25 or the locally built JARs are unavailable, so CI without a JVM stays green.
 *
 * It asserts, through the AgentOS REST API (via the Coday proxy, as the first-run user):
 *  - platform-scope providers and their 4 aliased models, `default` (a copy of SMALL), plus WHISPER (the transcription
 *    model) for requesty,
 *  - the personal namespace `user:<email>` with the ADMIN role,
 *  - the Coday agent deployed and default, with its three integrations (config, case files, namespace files),
 *  - `sharedExchangePath` set to the chosen folder.
 */

const PORT = Number(process.env['FIRST_RUN_E2E_PORT'] ?? 3977)
const BASE = `http://localhost:${PORT}`
const EMAIL = 'e2e@coday.test'
// Own throwaway root. CODAY_LOCAL_DIR is deliberately NOT inherited: the e2e runs with --fresh, which would wipe
// the everyday `web:local` setup of a developer who exports it. Override with FIRST_RUN_E2E_DIR only.
const ROOT = process.env['FIRST_RUN_E2E_DIR'] ?? path.join(os.tmpdir(), `coday-first-run-e2e-${process.pid}`)
const ROOT_IS_OURS = !process.env['FIRST_RUN_E2E_DIR']
const FILES_FOLDER = path.join(ROOT, 'files', 'Coday')
const WORKSPACE = path.resolve(__dirname, '../../..')

function javaMajor(): number {
  try {
    const bin = process.env['JAVA_HOME'] ? path.join(process.env['JAVA_HOME'], 'bin', 'java') : 'java'
    const out = execFileSync(bin, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    return parseInt(out.match(/^\S+\s+(\d+)/)?.[1] ?? '0', 10)
  } catch {
    return 0
  }
}

const jarsBuilt = (): boolean =>
  fs.existsSync(path.join(WORKSPACE, 'agentos/agentos-service/build/libs')) &&
  fs.existsSync(path.join(WORKSPACE, 'factory/factory-service/build/libs'))

test.describe.configure({ mode: 'serial' })

test.describe('first-run wizard', () => {
  let server: ChildProcess | undefined

  test.skip(javaMajor() < 25, 'Java 25+ is required to run AgentOS')
  test.skip(!jarsBuilt(), 'AgentOS/Factory JARs are not built (run: pnpm nx run web:build-first-run-jars)')

  test.beforeAll(async () => {
    test.setTimeout(10 * 60_000)
    fs.mkdirSync(ROOT, { recursive: true })
    // --fresh: the e2e always starts from a brand-new first run, whatever the root already holds.
    server = spawn('pnpm', ['exec', 'tsx', 'scripts/web-local.ts', '--fresh', '--managed', '--port', String(PORT)], {
      cwd: WORKSPACE,
      env: { ...process.env, CODAY_LOCAL_DIR: ROOT },
      stdio: 'inherit',
    })
    await expect
      .poll(async () => (await fetch(`${BASE}/api/setup/status`).catch(() => null))?.ok ?? false, {
        timeout: 8 * 60_000,
        intervals: [2_000],
      })
      .toBe(true)
  })

  test.afterAll(() => {
    server?.kill('SIGTERM')
    if (ROOT_IS_OURS) fs.rmSync(ROOT, { recursive: true, force: true })
  })

  const agentos = async <T>(apiPath: string, init?: RequestInit): Promise<T> => {
    const res = await fetch(`${BASE}/api/agentos${apiPath}`, init)
    expect(res.ok, `${apiPath} -> ${res.status}`).toBe(true)
    return (await res.json()) as T
  }

  test('walks the wizard and configures AgentOS', async ({ page }) => {
    test.setTimeout(10 * 60_000)
    await page.goto(`${BASE}/`)
    await expect(page).toHaveURL(/\/welcome$/)

    // 1. email
    await page.getByTestId('email-input').fill(EMAIL)
    await page.getByTestId('email-next').click()

    // 2. providers: two, ranked (anthropic first)
    await page.getByTestId('provider-toggle-anthropic').check()
    await page.getByTestId('provider-key-anthropic').fill('sk-ant-dummy-key-for-e2e')
    await page.getByTestId('provider-toggle-requesty').check()
    await page.getByTestId('provider-key-requesty').fill('rqsty-dummy-key-for-e2e')
    await page.getByTestId('providers-next').click()

    // 3. folder: the sandbox proposes a folder inside the temp dir (CODAY_DEFAULT_FILES_DIR), never ~/Coday
    await expect(page.getByTestId('selected-folder')).toContainText(FILES_FOLDER)
    await page.getByTestId('folder-next').click()

    // 4. live checklist, then the call to action
    await expect(page.getByTestId('checklist')).toBeVisible()
    await expect(page.getByTestId('start-with-coday')).toBeVisible({ timeout: 8 * 60_000 })
    await page.getByTestId('start-with-coday').click()
    await expect(page).toHaveURL(/\/home\?ns=/)
    // Landing must RENDER something (a blank page used to pass this test): the composer headline.
    await expect(page.getByRole('heading', { name: 'What can I help you with?' })).toBeVisible({ timeout: 120_000 })

    // --- AgentOS state, through the proxy as the first-run user ---------------------------------------
    const providers =
      await agentos<Array<{ id: string; name: string; namespaceId?: string | null }>>('/api/ai-providers')
    expect(providers.map((p) => p.name).sort()).toEqual(['anthropic', 'requesty'])
    for (const p of providers) expect(p.namespaceId ?? null).toBeNull()

    for (const provider of providers) {
      const models = await agentos<Array<{ alias?: string }>>(`/api/ai-models/by-parentId/${provider.id}`)
      // Chat aliases and `default` (a copy of SMALL) everywhere; requesty (like openai) also gets the WHISPER
      // transcription model, anthropic has none. Sorted: upper case before lower case.
      const chatAliases = ['BIG', 'BIGGEST', 'FRONTIER', 'SMALL']
      const expected =
        provider.name === 'requesty' ? [...chatAliases, 'WHISPER', 'default'] : [...chatAliases, 'default']
      expect(models.map((m) => m.alias).sort()).toEqual(expected)
    }

    const namespaces = await agentos<Array<{ id: string; sharedExchangePath?: string; defaultAgentName?: string }>>(
      '/api/namespaces/by-external-ids',
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify([`user:${EMAIL}`]) }
    )
    expect(namespaces).toHaveLength(1)
    const namespace = namespaces[0]!
    expect(namespace.defaultAgentName).toBe('Coday')
    expect(namespace.sharedExchangePath).toBe(FILES_FOLDER)
    expect(fs.existsSync(FILES_FOLDER)).toBe(true)

    const mine = await agentos<Array<{ id: string; role?: string }>>('/api/namespaces')
    expect(mine.find((n) => n.id === namespace.id)?.role).toBe('ADMIN')

    const agents = await agentos<Array<{ name: string; enabled?: boolean; integrations?: Record<string, unknown> }>>(
      '/api/agent-configs/search',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ namespaceId: namespace.id, userExternalId: EMAIL }),
      }
    )
    const coday = agents.find((a) => a.name === 'Coday')
    expect(coday?.enabled).not.toBe(false)
    expect(Object.keys(coday?.integrations ?? {}).sort()).toEqual([
      'AGENTOS_CONFIG',
      'CASE_FILE_EXCHANGE',
      'NAMESPACE_FILE_EXCHANGE',
    ])
  })

  test('once the setup is done, /welcome is a plain loading page that leaves for the app, and the setup API refuses mutations', async ({
    page,
  }) => {
    await page.goto(`${BASE}/welcome`)
    // Nothing of the wizard: no stepper, no setup steps. AgentOS is already up here, so it hands over at once.
    await expect(page.getByLabel('Setup progress')).toHaveCount(0)
    await expect(page).toHaveURL(/\/home/, { timeout: 60_000 })
    const browse = await fetch(`${BASE}/api/setup/browse?path=${encodeURIComponent(os.homedir())}`)
    expect(browse.status).toBe(403)
  })
})
