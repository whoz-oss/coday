import { expect, Page, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'
const CASE = { id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Existing conversation', status: 'IDLE', role: 'ADMIN' }

type WizzWindow = Window & { __wizzEmit: (type: string, event: object) => void }

async function mockBackend(page: Page): Promise<void> {
  await page.addInitScript(() => {
    class FakeEventSource extends EventTarget {
      readonly readyState = 1
      constructor() {
        super()
        ;(window as WizzWindow).__wizzEmit = (type, event) =>
          this.dispatchEvent(new MessageEvent(type, { data: JSON.stringify(event) }))
      }
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') return route.abort('blockedbyclient')
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
    } else if (path.endsWith('/workspaces') || path.endsWith('/integration-types')) {
      body = []
    } else {
      return route.fulfill({ status: 404, json: {} })
    }
    return route.fulfill({ json: body })
  })
}

async function emitEligibleCompletion(page: Page, id: string): Promise<void> {
  await page.evaluate(({ caseId, namespaceId, id: prefix }) => {
    const emit = (window as WizzWindow).__wizzEmit
    const base = { caseId, namespaceId, timestamp: '', metadata: { id: '', created: '', modified: '', removed: false } }
    emit('ThinkingEvent', { ...base, id: `${prefix}-thinking`, type: 'ThinkingEvent' })
    emit('MessageEvent', {
      ...base,
      id: `${prefix}-message`,
      type: 'MessageEvent',
      actor: { id: 'agent-1', role: 'AGENT', displayName: 'Agent' },
      content: [{ type: 'Text', content: 'Done' }],
    })
    emit('AgentFinishedEvent', {
      ...base,
      id: `${prefix}-finished`,
      type: 'AgentFinishedEvent',
      agentId: 'agent-1',
      agentName: 'Agent',
    })
  }, { caseId: CASE_ID, namespaceId: NAMESPACE_ID, id })
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page)
  await page.setViewportSize({ width: 1200, height: 736 })
})

test('Wizz is keyboard accessible and decorates only an observed completed response without changing scroll geometry', async ({ page }) => {
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
  const toggle = page.getByRole('button', { name: 'Wizz' })
  const region = page.locator('.case-chat__messages-region')
  await expect(toggle).toHaveAttribute('aria-pressed', 'false')
  await toggle.focus()
  await page.keyboard.press('Enter')
  await expect(toggle).toHaveAttribute('aria-pressed', 'true')

  const before = await region.evaluate((el) => ({
    scrollTop: el.querySelector<HTMLElement>('.case-chat__messages')!.scrollTop,
    width: el.getBoundingClientRect().width,
    height: el.getBoundingClientRect().height,
  }))
  await emitEligibleCompletion(page, 'first')
  await expect(region).toHaveClass(/case-chat__messages-region--wizz-one|case-chat__messages-region--wizz-two/)
  const during = await region.evaluate((el) => ({
    scrollTop: el.querySelector<HTMLElement>('.case-chat__messages')!.scrollTop,
    width: el.getBoundingClientRect().width,
    height: el.getBoundingClientRect().height,
  }))
  expect(during).toEqual(before)
  await expect(region).not.toHaveClass(/case-chat__messages-region--wizz-one|case-chat__messages-region--wizz-two/)
})

test('Wizz uses a stationary highlight when reduced motion is requested', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
  await page.getByRole('button', { name: 'Wizz' }).click()
  await emitEligibleCompletion(page, 'reduced')
  const state = await page.locator('.case-chat__messages-region').evaluate((el) => ({
    animation: getComputedStyle(el).animationName,
    transform: getComputedStyle(el).transform,
  }))
  expect(state.animation).toBe('case-chat-wizz-highlight')
  expect(state.transform).toBe('none')
})
