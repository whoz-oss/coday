import { expect, Page, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'

async function mockBackend(page: Page): Promise<void> {
  await page.addInitScript(() => {
    class FakeEventSource extends EventTarget {
      static sources: FakeEventSource[] = []
      readonly readyState = 1
      constructor(readonly url: string) {
        super()
        FakeEventSource.sources.push(this)
      }
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
    ;(window as Window & { fakeCaseEvents: (event: object) => void }).fakeCaseEvents = (event) => {
      for (const source of FakeEventSource.sources) {
        source.dispatchEvent(new MessageEvent('case-event', { data: JSON.stringify(event) }))
      }
    }
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const body = path === '/api/setup/status'
      ? { mode: 'unmanaged', setupEnabled: true, setupDone: true, steps: { agentos: { state: 'done' } } }
      : path.endsWith('/users/me')
        ? { id: 'user-1', externalId: 'user-1', email: 'e2e@example.test', firstname: 'Test', lastname: 'User' }
        : path.endsWith('/namespaces')
          ? [{ id: NAMESPACE_ID, name: 'Test namespace', role: 'ADMIN' }]
          : path.endsWith(`/cases/by-parentId/${NAMESPACE_ID}/mine`)
            ? [{ id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Existing conversation', status: 'IDLE', role: 'ADMIN' }]
            : path.endsWith('/directory') ? { path: '', entries: [], totalEntries: 0, hasMore: false, page: 0, capability: 'READ_WRITE' }
            : path.endsWith('/voice-messages/capability') ? { available: false }
            : path.endsWith('/usage-configuration') || path.endsWith('/git-workspaces-configuration') ? { enabled: false }
            : path.endsWith('/workspaces') || path.endsWith('/integration-types') ? [] : undefined
    if (body === undefined) await route.fulfill({ status: 404, json: {} })
    else await route.fulfill({ json: body })
  })
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page)
  await page.setViewportSize({ width: 1200, height: 736 })
})

test('Wizz is keyboard accessible and shakes only the conversation region without changing its scroll geometry', async ({ page }) => {
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
  const toggle = page.getByRole('button', { name: 'Wizz on completed agent replies' })
  const region = page.locator('.case-chat__messages-region')
  await expect(toggle).toHaveAttribute('aria-pressed', 'false')
  await toggle.focus()
  await page.keyboard.press('Enter')
  await expect(toggle).toHaveAttribute('aria-pressed', 'true')

  const before = await region.evaluate((element) => ({ scrollTop: element.scrollTop, scrollHeight: element.scrollHeight, box: element.getBoundingClientRect().toJSON() }))
  await page.evaluate(() => (window as Window & { fakeCaseEvents: (event: object) => void }).fakeCaseEvents({ id: 'run-1', type: 'AgentRunningEvent', caseId: '22222222-2222-4222-8222-222222222222' }))
  await page.evaluate(() => (window as Window & { fakeCaseEvents: (event: object) => void }).fakeCaseEvents({ id: 'message-1', type: 'MessageEvent', caseId: '22222222-2222-4222-8222-222222222222', actor: { id: 'agent-1', role: 'AGENT' }, content: [{ type: 'Text', content: 'Done' }] }))
  await page.evaluate(() => (window as Window & { fakeCaseEvents: (event: object) => void }).fakeCaseEvents({ id: 'finish-1', type: 'AgentFinishedEvent', caseId: '22222222-2222-4222-8222-222222222222', agentId: 'agent-1' }))
  await expect(region).toHaveClass(/case-chat__messages-region--wizz/)
  const during = await region.evaluate((element) => ({ scrollTop: element.scrollTop, scrollHeight: element.scrollHeight, transform: getComputedStyle(element).transform }))
  expect(during.scrollTop).toBe(before.scrollTop)
  expect(during.scrollHeight).toBeGreaterThanOrEqual(before.scrollHeight)
  expect(during.transform).not.toBe('none')

  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.waitForTimeout(400)
  await page.keyboard.press('Space')
  await expect(toggle).toHaveAttribute('aria-pressed', 'false')
  await page.keyboard.press('Space')
  await page.evaluate(() => (window as Window & { fakeCaseEvents: (event: object) => void }).fakeCaseEvents({ id: 'message-2', type: 'MessageEvent', caseId: '22222222-2222-4222-8222-222222222222', actor: { id: 'agent-1', role: 'AGENT' }, content: [{ type: 'Text', content: 'Again' }] }))
  await page.evaluate(() => (window as Window & { fakeCaseEvents: (event: object) => void }).fakeCaseEvents({ id: 'finish-2', type: 'AgentFinishedEvent', caseId: '22222222-2222-4222-8222-222222222222', agentId: 'agent-1' }))
  await expect(region).toHaveClass(/case-chat__messages-region--wizz/)
  expect(await region.evaluate((element) => getComputedStyle(element).transform)).toBe('none')
})
