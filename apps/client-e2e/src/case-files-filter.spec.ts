import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'

// TC-010: browser test with synthetic, read-only API responses, not a real backend permission test.
test('TC-010 — case file search restores navigation and leaves namespace search unchanged', async ({ page }) => {
  const caseId = randomUUID()
  const namespaceId = randomUUID()
  const caseBase = `/api/agentos/api/cases/${caseId}/files`
  const namespaceBase = `/api/agentos/api/namespaces/${namespaceId}/files`
  const guidePath = 'docs/api/guide.md'
  const content = 'Synthetic TC-010 guide content'
  const entry = (path: string, directory = false) => ({
    path,
    name: path.split('/').pop(),
    directory,
    ...(directory ? {} : { size: 32, lastModified: '2026-01-01T00:00:00Z' }),
  })
  const manifestFile = (path: string, scope: 'CASE' | 'NAMESPACE') => ({
    path,
    filename: path.split('/').pop(),
    scope,
    size: 32,
    lastModified: '2026-01-01T00:00:00Z',
  })

  // Intercept every AgentOS API request: no synthetic ID or mutation reaches a real service.
  await page.route('**/api/agentos/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() !== 'GET') {
      await route.fulfill({ status: 405, json: { message: 'Unexpected non-GET request in TC-010' } })
      return
    }
    let body: unknown
    let status = 200
    if (url.pathname === `${caseBase}/directory`) {
      const path = url.searchParams.get('path') ?? ''
      const entries = path === '' ? [entry('current', true), entry('docs', true)] :
        path === 'current' ? [entry('current/notes.txt')] : null
      if (entries) body = { path, entries, totalEntries: entries.length, page: 0, pageSize: 200, hasMore: false, capability: 'READ' }
      else { status = 404; body = { message: 'Unknown synthetic directory' } }
    } else if (url.pathname === `${namespaceBase}/directory`) {
      body = { path: '', entries: [entry('shared.txt')], totalEntries: 1, page: 0, pageSize: 200, hasMore: false, capability: 'READ' }
    } else if (url.pathname === `${caseBase}/manifest`) {
      body = { capability: 'READ', files: [manifestFile(guidePath, 'CASE'), manifestFile('current/notes.txt', 'CASE')] }
    } else if (url.pathname === `${namespaceBase}/manifest`) {
      body = { capability: 'READ', files: [manifestFile('shared.txt', 'NAMESPACE'), manifestFile(guidePath, 'NAMESPACE')] }
    } else if ((url.pathname === `${caseBase}/content` || url.pathname === `${namespaceBase}/content`) && url.searchParams.get('path') === guidePath) {
      body = { content, size: 32, mimeType: 'text/markdown', etag: 'synthetic' }
    } else if (url.pathname === '/api/agentos/api/git-workspaces-configuration') {
      body = { enabled: false }
    } else if (url.pathname === '/api/agentos/api/usage-configuration') {
      body = { enabled: false }
    } else {
      status = 404
      body = { message: 'Not provided by TC-010 mock' }
    }
    await route.fulfill({ status, json: body })
  })
  await page.route('**/api/setup/status', (route) => route.fulfill({
    json: { mode: 'managed', setupEnabled: false, setupDone: true, steps: { agentos: { state: 'done' } } },
  }))

  await page.goto(`/home?ns=${namespaceId}&case=${caseId}`)
  await page.locator('[data-track="CaseChat-openFiles"]').click()
  const caseSection = page.locator('[data-track="ExchangeDrawer-caseSection"]')
  const namespaceSection = page.locator('[data-track="ExchangeDrawer-namespaceSection"]')
  const caseFilter = page.locator('[data-track="ExchangeDrawer-caseFilterInput"]')
  const namespaceFilter = page.locator('[data-track="ExchangeDrawer-namespaceFilterInput"]')

  await page.locator('[data-track="ExchangeDrawer-folder_CASE:current"]').click()
  await expect(caseSection.locator('[data-track="ExchangeFolderNav-current"]')).toHaveText('current')
  await expect(page.locator('[data-track="ExchangeItem-file_CASE:current/notes.txt"]')).toBeVisible()

  await namespaceFilter.fill('shared')
  await expect(page.locator('[data-track="ExchangeItem-file_NAMESPACE:shared.txt"]')).toBeVisible()
  await caseFilter.fill('guide')
  await expect(page.locator('[data-track="ExchangeDrawer-filteredFolder_CASE:docs"]')).toBeVisible()
  await expect(page.locator('[data-track="ExchangeDrawer-filteredFolder_CASE:docs/api"]')).toBeVisible()
  await expect(page.locator('[data-track="ExchangeItem-file_CASE:current/notes.txt"]')).toHaveCount(0)
  await page.locator('[data-track="ExchangeItem-file_CASE:docs/api/guide.md"]').click()
  await expect(page.getByText(content)).toBeVisible()
  await page.locator('[data-track="ExchangeContentViewer-back"]').click()
  await expect(caseFilter).toHaveValue('guide')
  await expect(page.locator('[data-track="ExchangeItem-file_CASE:docs/api/guide.md"]')).toBeVisible()
  await expect(namespaceFilter).toHaveValue('shared')
  await expect(namespaceSection.locator('[data-track="ExchangeItem-file_NAMESPACE:shared.txt"]')).toBeVisible()
  await page.locator('[data-track="ExchangeDrawer-caseClearFilter"]').click()
  await expect(caseFilter).toHaveValue('')
  await expect(caseFilter).toBeFocused()
  await expect(caseSection.locator('[role="status"][aria-live="polite"]')).toHaveText('Case files: back to file list.')
  await expect(caseSection.locator('[data-track="ExchangeFolderNav-current"]')).toHaveText('current')
  await expect(page.locator('[data-track="ExchangeItem-file_CASE:current/notes.txt"]')).toBeVisible()
  await expect(namespaceFilter).toHaveValue('shared')
  await expect(namespaceSection.locator('[data-track="ExchangeItem-file_NAMESPACE:shared.txt"]')).toBeVisible()

  // Both scopes contain the same path; returning from either preview must focus its own row.
  await caseFilter.fill('guide')
  await namespaceFilter.fill('guide')
  const caseRow = page.locator('[data-track="ExchangeItem-file_CASE:docs/api/guide.md"]')
  const namespaceRow = page.locator('[data-track="ExchangeItem-file_NAMESPACE:docs/api/guide.md"]')
  await expect(caseRow).toBeVisible()
  await expect(namespaceRow).toBeVisible()
  for (const row of [caseRow, namespaceRow]) {
    await row.click()
    await expect(page.getByText(content)).toBeVisible()
    await page.locator('[data-track="ExchangeContentViewer-back"]').click()
    await expect(row).toBeFocused()
  }

  const caseStatus = caseSection.locator('[role="status"][aria-live="polite"]')
  await expect(caseStatus).toHaveText('Case files: 1 match for "guide".')
  await caseFilter.fill('absent')
  await expect(caseStatus).toHaveText('Case files: 0 matches for "absent".')
  await caseFilter.fill('docs')
  await expect(caseStatus).toHaveText('Case files: 1 match for "docs".')
  await caseFilter.focus()
  await caseFilter.press('Tab')
  const clear = page.locator('[data-track="ExchangeDrawer-caseClearFilter"]')
  await expect(clear).toBeFocused()
  await clear.press('Enter')
  await expect(caseFilter).toBeFocused()
  await expect(caseStatus).toHaveText('Case files: back to file list.')
  await expect(namespaceFilter).toHaveValue('guide')
})
