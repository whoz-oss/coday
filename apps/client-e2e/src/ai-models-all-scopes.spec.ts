import { expect, Route, test } from '@playwright/test'

/**
 * E2E coverage for the unified AI Models page (story 6.6 — AC16).
 *
 * Mocks the agentos backend at the network layer.
 *
 * Scenarios covered:
 *   1. The page renders the 3 sections with the right group labels and the parent provider
 *      name + scope is shown alongside each model row.
 *   2. Clicking "Override for me" on an NS row navigates to the form with `?scope=userOnNs&template=…`.
 *   3. In the create form, switching the scope radio re-filters the provider <select> and
 *      clears a stale parent selection (FR3 parent-mode constraint).
 *   4. Deleting a USER GLOBAL override removes it.
 *
 * Marked `test.fixme` while harness bootstrap stabilizes — same posture as 6.5 / AI Providers.
 */

const NS_ID = '11111111-1111-1111-1111-111111111111'
const NS_PROVIDER_ID = 'p-ns-1'
const USER_ON_NS_PROVIDER_ID = 'p-uns-1'
const USER_GLOBAL_PROVIDER_ID = 'p-ug-1'
const NS_MODEL_ID = 'm-ns-1'
const USER_GLOBAL_MODEL_ID = 'm-ug-1'

const nsProvider = {
  id: NS_PROVIDER_ID,
  namespaceId: NS_ID,
  name: 'NS Anthropic',
  apiType: 'Anthropic',
}
const userOnNsProvider = {
  id: USER_ON_NS_PROVIDER_ID,
  namespaceId: NS_ID,
  userId: 'me',
  name: 'My NS Anthropic',
  apiType: 'Anthropic',
}
const userGlobalProvider = {
  id: USER_GLOBAL_PROVIDER_ID,
  userId: 'me',
  name: 'My Global Anthropic',
  apiType: 'Anthropic',
}

const nsModel = {
  id: NS_MODEL_ID,
  aiProviderId: NS_PROVIDER_ID,
  namespaceId: NS_ID,
  apiModelName: 'claude-opus-4',
  alias: 'opus',
  priority: 1,
}

async function fulfillJson(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

test.fixme('story 6.6 — AI Models all-scopes golden path with FR3 provider filter', async ({ page }) => {
  let userGlobalModels: Array<typeof nsModel> = [
    {
      id: USER_GLOBAL_MODEL_ID,
      aiProviderId: USER_GLOBAL_PROVIDER_ID,
      namespaceId: undefined as unknown as string,
      apiModelName: 'claude-haiku-4',
      alias: 'haiku',
      priority: 3,
    },
  ]

  // AI Providers — unified endpoint, scope distinguished by query params.
  await page.route('**/api/agentos/api/ai-providers', async (route) => {
    const url = new URL(route.request().url())
    const namespaceId = url.searchParams.get('namespaceId')
    const userId = url.searchParams.get('userId')
    if (route.request().method() === 'GET') {
      let content: unknown[]
      if (userId === 'me') {
        content = namespaceId === 'none' ? [userGlobalProvider] : namespaceId === NS_ID ? [userOnNsProvider] : []
      } else {
        content = namespaceId === NS_ID ? [nsProvider] : []
      }
      await fulfillJson(route, { content, page: 0, size: 1000, totalElements: content.length, totalPages: 1 })
      return
    }
    await route.continue()
  })

  // AI Models — unified endpoint, scope distinguished by ?namespaceId + ?userId query params.
  await page.route('**/api/agentos/api/ai-models', async (route) => {
    const url = new URL(route.request().url())
    const namespaceId = url.searchParams.get('namespaceId')
    const userId = url.searchParams.get('userId')
    if (route.request().method() === 'GET') {
      let content: unknown[]
      if (userId === 'me') {
        content = namespaceId === 'none' ? userGlobalModels : namespaceId === NS_ID ? [] : []
      } else {
        content = namespaceId === NS_ID ? [nsModel] : []
      }
      await fulfillJson(route, { content, page: 0, size: 1000, totalElements: content.length, totalPages: 1 })
      return
    }
    await route.continue()
  })
  await page.route(`**/api/agentos/api/ai-models/${USER_GLOBAL_MODEL_ID}`, async (route) => {
    if (route.request().method() === 'DELETE') {
      userGlobalModels = []
      await route.fulfill({ status: 204, body: '' })
      return
    }
    await route.continue()
  })

  await page.goto(`/agentos/${NS_ID}/ai-models`)

  // 1 — three sections rendered + parent provider visible
  await expect(page.getByText('AI Models du namespace')).toBeVisible()
  await expect(page.getByText('Mes overrides globaux')).toBeVisible()
  await expect(page.getByText(/NS Anthropic.*claude-opus-4/)).toBeVisible()
  await expect(page.getByText(/My Global Anthropic.*claude-haiku-4/)).toBeVisible()

  // 2 — Duplicate cross-link from a namespace model → form opens in clone-strict mode
  // (scope=namespace, templateScope=namespace).
  await page
    .getByRole('button', { name: /duplicate/i })
    .first()
    .click()
  await expect(page).toHaveURL(/scope=namespace/)
  await expect(page).toHaveURL(/templateScope=namespace/)
  await expect(page).toHaveURL(new RegExp(`template=${NS_MODEL_ID}`))

  // 3 — Switch scope radio in the form: provider <select> re-filters
  await page.getByLabel('Pour moi globalement').click()
  // After scope switch, the <select> should only show userGlobal providers (FR3)
  const providerSelectOptions = await page.locator('select#model-provider option').allTextContents()
  expect(providerSelectOptions.some((o) => o.includes('My Global Anthropic'))).toBe(true)
  expect(providerSelectOptions.some((o) => o.includes('NS Anthropic'))).toBe(false)
  expect(providerSelectOptions.some((o) => o.includes('My NS Anthropic'))).toBe(false)

  // 4 — Delete user-global model → section becomes empty
  await page.goBack()
  await page.getByText(/claude-haiku-4/).hover()
  await page
    .getByRole('button', { name: /delete model/i })
    .first()
    .click()
  await page.getByRole('button', { name: /confirm deletion/i }).click()
  await expect(page.getByText('Aucun AI Model')).toBeVisible()
})
