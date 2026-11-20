import { expect, Page, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'

async function mockBackend(page: Page): Promise<void> {
  await page.addInitScript(() => {
    class FakeEventSource extends EventTarget {
      static latest: FakeEventSource
      readonly readyState = 1
      constructor() {
        super()
        FakeEventSource.latest = this
      }
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    let body: unknown
    if (path === '/api/setup/status') {
      body = { mode: 'unmanaged', setupEnabled: true, setupDone: true, steps: { agentos: { state: 'done' } } }
    } else if (path.endsWith('/users/me')) {
      body = { id: 'user-1', email: 'e2e@example.test', firstname: 'Test', lastname: 'User' }
    } else if (path.endsWith('/namespaces')) {
      body = [{ id: NAMESPACE_ID, name: 'Test namespace', role: 'ADMIN' }]
    } else if (path.endsWith(`/cases/by-parentId/${NAMESPACE_ID}/mine`)) {
      body = [{ id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Wizz case', status: 'IDLE', role: 'ADMIN' }]
    } else if (path.endsWith(`/cases/${CASE_ID}`)) {
      body = { id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Wizz case', status: 'IDLE' }
    } else if (path.endsWith('/directory')) {
      body = { path: '', entries: [], totalEntries: 0, hasMore: false, page: 0, capability: 'READ_WRITE' }
    } else if (path.endsWith('/voice-messages/capability')) {
      body = { available: false }
    } else if (path.endsWith('/usage-configuration') || path.endsWith('/git-workspaces-configuration')) {
      body = { enabled: false }
    } else if (path.endsWith('/workspaces') || path.endsWith('/integration-types')) {
      body = []
    }
    if (body === undefined) await route.fulfill({ status: 404, json: {} })
    else await route.fulfill({ json: body })
  })
}

async function emitLiveCompletion(page: Page): Promise<void> {
  await page.evaluate(({ caseId, namespaceId }) => {
    const source = (window.EventSource as unknown as { latest: EventTarget }).latest
    const emit = (type: string, id: string, fields: object = {}) =>
      source.dispatchEvent(new MessageEvent(type, { data: JSON.stringify({ type, id, caseId, namespaceId, timestamp: '', metadata: {}, ...fields }) }))
    emit('ThinkingEvent', 'thinking-1')
    emit('MessageEvent', 'message-1', { actor: { role: 'AGENT', id: 'agent-1' }, content: [{ content: 'Done' }] })
    emit('AgentFinishedEvent', 'finished-1', { agentId: 'agent-1', agentName: 'Agent' })
  }, { caseId: CASE_ID, namespaceId: NAMESPACE_ID })
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page)
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
})

test('Wizz is an accessible default-off toggle and pulses only after a live completion', async ({ page }) => {
  const toggle = page.getByRole('button', { name: 'Wizz' })
  await expect(toggle).toHaveAttribute('aria-pressed', 'false')
  await toggle.focus()
  await page.keyboard.press('Enter')
  await expect(toggle).toHaveAttribute('aria-pressed', 'true')
  await emitLiveCompletion(page)
  await expect(page.locator('.case-chat__messages-region')).toHaveClass(/case-chat__messages-region--wizzing/)
})

test('reduced motion uses the stationary Wizz highlight', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.getByRole('button', { name: 'Wizz' }).click()
  await emitLiveCompletion(page)
  const animation = await page.locator('.case-chat__messages-region').evaluate((element) => getComputedStyle(element).animationName)
  expect(animation).toBe('case-chat-wizz-highlight')
})
