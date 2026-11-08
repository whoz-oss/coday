import { expect, test } from './real-instance.fixture'

test('TC-010 — real reader searches case files and restores navigation independently of namespace', async ({ browser, realInstance }) => {
  const { baseURL, reader, caseId, namespaceId, content } = realInstance
  const context = await browser.newContext({ extraHTTPHeaders: { 'x-forwarded-email': reader } })
  try {
    const page = await context.newPage()
    await page.goto(`${baseURL}/home?ns=${namespaceId}&case=${caseId}`)
    await page.locator('[data-track="CaseChat-openFiles"]').click()
    const caseSection = page.locator('[data-track="ExchangeDrawer-caseSection"]')
    const namespaceSection = page.locator('[data-track="ExchangeDrawer-namespaceSection"]')
    const caseFilter = page.locator('[data-track="ExchangeDrawer-caseFilterInput"]')
    const namespaceFilter = page.locator('[data-track="ExchangeDrawer-namespaceFilterInput"]')
    await page.locator('[data-track="ExchangeDrawer-folder_CASE:current"]').click()
    await expect(caseSection.locator('[data-track="ExchangeFolderNav-current"]')).toHaveText('current')
    await expect(page.locator('[data-track="ExchangeItem-file_CASE:current/notes.txt"]')).toBeVisible()
    await namespaceFilter.fill('shared')
    await expect(namespaceSection.locator('[data-track="ExchangeItem-file_NAMESPACE:shared.txt"]')).toBeVisible()
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
    await expect(caseSection.locator('[data-track="ExchangeFolderNav-current"]')).toHaveText('current')
    await expect(page.locator('[data-track="ExchangeItem-file_CASE:current/notes.txt"]')).toBeVisible()
    await expect(namespaceFilter).toHaveValue('shared')
    await expect(namespaceSection.locator('[data-track="ExchangeItem-file_NAMESPACE:shared.txt"]')).toBeVisible()
  } finally {
    await context.close()
  }
})
