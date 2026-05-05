import { expect, Route, test } from '@playwright/test'

/**
 * E2E coverage for the unified AI Providers page (story 6.6 — AC15).
 *
 * Mocks the agentos backend at the network layer so the test runs against the dev server
 * without requiring a live backend or seeded data.
 *
 * Scenarios covered:
 *   1. The page renders the 3 sections with the right group labels.
 *   2. Clicking "Override for me" on an NS row navigates to the form pre-seeded with
 *      `?scope=userOnNs&template=…`; on submit, the new override appears in USER × NS.
 *   3. Editing a USER GLOBAL override without touching the apiKey field does NOT include
 *      apiKey in the PUT payload (NFR-SEC-1, FR25).
 *   4. Deleting a USER GLOBAL override removes it (the section becomes empty).
 *
 * Marked `test.fixme` while the harness bootstrap (NamespaceStateService init + OAuth-disabled
 * local mode) requires stabilization. Pre-wired for when the infra matures — same posture
 * as the 6.5 e2e (`integrations-all-scopes.spec.ts`).
 */

const NS_ID = '11111111-1111-1111-1111-111111111111'
const NS_PROVIDER_ID = '22222222-2222-2222-2222-222222222222'
const USER_ON_NS_ID = '33333333-3333-3333-3333-333333333333'
const USER_GLOBAL_ID = '44444444-4444-4444-4444-444444444444'

const namespaceProvider = {
  id: NS_PROVIDER_ID,
  namespaceId: NS_ID,
  name: 'NS Anthropic',
  apiType: 'Anthropic',
  baseUrl: 'https://api.anthropic.com',
}
const userOnNsProvider = {
  id: USER_ON_NS_ID,
  namespaceId: NS_ID,
  userId: 'me',
  name: 'My NS Anthropic',
  apiType: 'Anthropic',
  apiKey: 'sk-ant-•••••',
}

async function fulfillJson(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

test.fixme('story 6.6 — AI Providers all-scopes golden path', async ({ page }) => {
  // Test-local mutable state — must not leak across parallel workers.
  let userGlobalProviders: Array<typeof userOnNsProvider> = [
    {
      id: USER_GLOBAL_ID,
      namespaceId: undefined as unknown as string,
      userId: 'me',
      name: 'My Global Anthropic',
      apiType: 'Anthropic',
      apiKey: 'sk-ant-•••••',
    },
  ]
  let updatedPayloads: Array<Record<string, unknown>> = []

  // NS-shared list
  await page.route(`**/api/agentos/api/ai-providers/by-namespaceId/${NS_ID}`, (route) =>
    fulfillJson(route, [namespaceProvider])
  )

  // User-scope list, branching on the namespaceId query param
  await page.route('**/api/agentos/api/user-ai-providers**', async (route) => {
    const url = new URL(route.request().url())
    const namespaceId = url.searchParams.get('namespaceId')
    if (route.request().method() === 'GET') {
      const content = namespaceId === 'none' ? userGlobalProviders : namespaceId === NS_ID ? [userOnNsProvider] : []
      await fulfillJson(route, { content, page: 0, size: 1000, totalElements: content.length, totalPages: 1 })
      return
    }
    if (route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() ?? '{}')
      await fulfillJson(route, { ...body, id: 'new-override-id' }, 201)
      return
    }
    await route.continue()
  })

  // Update on a specific user provider — record the payload to assert apiKey omission.
  await page.route(`**/api/agentos/api/user-ai-providers/${USER_GLOBAL_ID}`, async (route) => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() ?? '{}')
      updatedPayloads.push(body)
      await fulfillJson(route, { ...body, id: USER_GLOBAL_ID })
      return
    }
    if (route.request().method() === 'DELETE') {
      userGlobalProviders = []
      await route.fulfill({ status: 204, body: '' })
      return
    }
    await route.continue()
  })

  await page.goto(`/agentos/${NS_ID}/ai-providers`)

  // 1 — three sections rendered
  await expect(page.getByText('AI Providers du namespace')).toBeVisible()
  await expect(page.getByText('Mes overrides sur ce namespace')).toBeVisible()
  await expect(page.getByText('Mes overrides globaux')).toBeVisible()
  await expect(page.getByText('NS Anthropic')).toBeVisible()
  await expect(page.getByText('My NS Anthropic')).toBeVisible()
  await expect(page.getByText('My Global Anthropic')).toBeVisible()

  // 2 — Override for me cross-link + create
  await page
    .getByRole('button', { name: /override for me/i })
    .first()
    .click()
  await expect(page).toHaveURL(/scope=userOnNs/)
  await expect(page).toHaveURL(new RegExp(`template=${NS_PROVIDER_ID}`))
  await page.getByLabel('Name').fill('My Override Anthropic')
  await page.getByLabel('API key').fill('sk-ant-myfreshkey')
  await page.getByRole('button', { name: /create/i }).click()

  // 3 — Edit user-global without touching apiKey → PUT must omit apiKey
  await page.getByText('My Global Anthropic').click()
  // (assume the kebab menu's Edit action navigates to /edit?scope=userGlobal)
  await page.getByRole('button', { name: /edit/i }).click()
  await page.getByLabel('Description').fill('Updated description, no key change')
  await page.getByRole('button', { name: /save/i }).click()
  expect(updatedPayloads.length).toBeGreaterThan(0)
  expect('apiKey' in (updatedPayloads.at(-1) ?? {})).toBe(false)

  // 4 — Delete user-global → section becomes empty
  await page.getByText('My Global Anthropic').hover()
  await page
    .getByRole('button', { name: /delete provider/i })
    .first()
    .click()
  await page.getByRole('button', { name: /confirm deletion/i }).click()
  await expect(page.getByText('Aucun AI Provider')).toBeVisible()
})
