import { expect, Locator, Page, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'
const DRAFT = Array.from({ length: 80 }, (_, i) => `Line ${i + 1}`).join('\n')
const CASE = { id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Existing conversation', status: 'IDLE', role: 'ADMIN' }

/** Isolate the UI from all real APIs, including mutations and event streams. */
async function mockBackend(page: Page): Promise<void> {
  await page.addInitScript(() => {
    class FakeEventSource extends EventTarget {
      readonly readyState = 1
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') {
      await route.abort('blockedbyclient')
      return
    }
    let body: unknown
    if (path === '/api/setup/status') {
      body = { mode: 'unmanaged', setupEnabled: true, setupDone: true, steps: { agentos: { state: 'done' } } }
    } else if (path.endsWith('/users/me')) {
      body = { id: 'user-1', externalId: 'user-1', email: 'e2e@example.test', firstname: 'Test', lastname: 'User' }
    } else if (path.endsWith('/namespaces')) {
      body = [{ id: NAMESPACE_ID, name: 'Test namespace', role: 'ADMIN' }]
    } else if (path.endsWith(`/cases/by-parentId/${NAMESPACE_ID}/mine`)) {
      body = [CASE]
    } else if (path.endsWith(`/cases/${CASE_ID}`)) {
      body = CASE
    } else if (path.endsWith('/directory')) {
      body = { path: '', entries: [], totalEntries: 0, hasMore: false, page: 0, capability: 'READ_WRITE' }
    } else if (path.endsWith('/voice-messages/capability')) {
      body = { available: false }
    } else if (path.endsWith('/usage-configuration') || path.endsWith('/git-workspaces-configuration')) {
      body = { enabled: false }
    } else if (path.endsWith('/workspaces')) {
      body = []
    } else if (path.endsWith('/integration-types')) {
      body = []
    } else {
      await route.fulfill({ status: 404, json: {} })
      return
    }
    await route.fulfill({ json: body })
  })
}

/** Check auto growth, both scroll extremes, and shrinking without submitting the draft. */
async function checkComposer(input: Locator, maximumRatio: number): Promise<void> {
  await input.fill('short')
  const shortHeight = await input.evaluate((el) => el.getBoundingClientRect().height)
  await input.fill(DRAFT)
  const longState = await input.evaluate((el) => ({
    height: el.getBoundingClientRect().height,
    range: el.scrollHeight - el.clientHeight,
    overflow: getComputedStyle(el).overflowY,
  }))
  expect(longState.height).toBeGreaterThan(shortHeight)
  expect(longState.height).toBeLessThanOrEqual(736 * maximumRatio + 1)
  expect(longState.range).toBeGreaterThan(0)
  expect(longState.overflow).toBe('auto')
  const firstLine = await input.evaluate((el) => {
    el.scrollTop = 0
    const box = el.getBoundingClientRect()
    return box.top + parseFloat(getComputedStyle(el).paddingTop)
  })
  expect(firstLine).toBeGreaterThanOrEqual(0)
  const lastLine = await input.evaluate((el) => {
    el.scrollTop = el.scrollHeight
    const box = el.getBoundingClientRect()
    const style = getComputedStyle(el)
    return {
      bottom: box.top + parseFloat(style.paddingTop) + 80 * parseFloat(style.lineHeight) - el.scrollTop,
      boxBottom: box.bottom,
      remaining: el.scrollHeight - el.clientHeight - el.scrollTop,
    }
  })
  expect(lastLine.remaining).toBeLessThanOrEqual(1)
  expect(lastLine.bottom).toBeLessThanOrEqual(Math.min(lastLine.boxBottom, 736))
  await input.evaluate((el) => (el.scrollTop = 0))
  expect(await input.evaluate((el) => el.scrollTop)).toBe(0)
  await expect(input).toHaveValue(DRAFT)
  await input.fill('short')
  expect(await input.evaluate((el) => el.getBoundingClientRect().height)).toBeLessThan(longState.height)
  await input.fill(DRAFT)
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page)
  await page.setViewportSize({ width: 1200, height: 736 })
})

test('new case: a long draft and its controls remain reachable', async ({ page }) => {
  await page.goto(`/home?ns=${NAMESPACE_ID}`)
  const input = page.getByPlaceholder('Describe your task or ask a question…')
  await expect(input).toBeVisible()
  await input.fill(Array.from({ length: 39 }, (_, i) => `Line ${i + 1}`).join('\n'))
  expect(await input.evaluate((el) => el.getBoundingClientRect().top)).toBeGreaterThanOrEqual(0)
  await checkComposer(input, 0.65)
  const heading = page.getByRole('heading', { name: 'What can I help you with?' })
  const send = page.getByRole('button', { name: 'Start case' })
  await expect(heading).toBeInViewport()
  await expect(send).toBeInViewport()
  await page.setViewportSize({ width: 1200, height: 300 })
  await heading.scrollIntoViewIfNeeded()
  await expect(heading).toBeInViewport()
  await send.scrollIntoViewIfNeeded()
  await expect(send).toBeInViewport()
  await expect(input).toHaveValue(DRAFT)
})

test('open case: a long reply and its controls remain reachable', async ({ page }) => {
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
  const input = page.getByPlaceholder('Reply…')
  await expect(page.getByRole('button', { name: 'Existing conversation' }).last()).toBeVisible()
  await expect(input).toBeEnabled()
  await checkComposer(input, 0.4)
  const send = page.locator('.case-chat__composer').getByRole('button').last()
  await expect(send).toBeInViewport()
  await page.setViewportSize({ width: 1200, height: 300 })
  const messagesHeight = await page.locator('.case-chat__messages-region').evaluate((el) => el.getBoundingClientRect().height)
  expect(messagesHeight).toBeGreaterThanOrEqual(96)
  await send.scrollIntoViewIfNeeded()
  await expect(send).toBeInViewport()
  await expect(input).toHaveValue(DRAFT)
})
