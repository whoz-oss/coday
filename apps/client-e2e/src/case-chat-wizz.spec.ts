import { expect, Page, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'

async function mockChat(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const streams: Array<{ url: string; source: EventTarget }> = []
    class FakeEventSource extends EventTarget {
      readonly readyState = 1
      constructor(readonly url: string) {
        super()
        streams.push({ url, source: this })
      }
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
    ;(window as unknown as { wizzStreams: typeof streams }).wizzStreams = streams
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const body = path.endsWith('/users/me')
      ? { id: 'user-1', email: 'e2e@example.test' }
      : path.endsWith('/namespaces')
        ? [{ id: NAMESPACE_ID, name: 'Test namespace', role: 'ADMIN' }]
        : path.endsWith(`/cases/by-parentId/${NAMESPACE_ID}/mine`) || path.endsWith(`/cases/${CASE_ID}`)
          ? [{ id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Existing conversation', status: 'IDLE', role: 'ADMIN' }]
          : path.endsWith('/directory')
            ? { path: '', entries: [], totalEntries: 0, hasMore: false, page: 0, capability: 'READ_WRITE' }
            : path.endsWith('/voice-messages/capability')
              ? { available: false }
              : { enabled: false }
    await route.fulfill({ json: body })
  })
}

test('Wizz is opt-in, uses a live-only stream and keeps the conversation geometry stationary', async ({ page }) => {
  await mockChat(page)
  await page.setViewportSize({ width: 390, height: 500 })
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
  const toggle = page.getByRole('button', { name: 'Wizz reply effect' })
  await expect(toggle).toHaveAttribute('aria-pressed', 'false')
  const before = await page.locator('.case-chat__messages-region').evaluate((el) => el.getBoundingClientRect().toJSON())
  await toggle.press('Space')
  await expect(toggle).toHaveAttribute('aria-pressed', 'true')
  const urls = await page.evaluate(() => (window as unknown as { wizzStreams: Array<{ url: string }> }).wizzStreams.map((s) => s.url))
  expect(urls.some((url) => url.includes('includePreviousEvents=false'))).toBe(true)
  const after = await page.locator('.case-chat__messages-region').evaluate((el) => el.getBoundingClientRect().toJSON())
  expect(after).toEqual(before)
})

test('reduced motion Wizz uses its stationary highlight rule', async ({ page }) => {
  await mockChat(page)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
  await page.getByRole('button', { name: 'Wizz reply effect' }).click()
  const stationary = await page.locator('.case-chat__messages-region').evaluate((el) => {
    el.classList.add('case-chat__messages-region--wizz')
    const style = getComputedStyle(el)
    return { animation: style.animationName, transform: style.transform }
  })
  expect(stationary.animation).toContain('case-chat-wizz-highlight')
  expect(stationary.transform).toBe('none')
})
