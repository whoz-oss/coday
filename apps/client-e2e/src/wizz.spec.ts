import { expect, Page, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'
const CASE = { id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Wizz conversation', status: 'IDLE', role: 'ADMIN' }

async function mockBackend(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const sources = new Map<string, EventTarget>()
    class FakeEventSource extends EventTarget {
      readonly readyState = 1
      constructor(url: string) {
        super()
        sources.set(url, this)
      }
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
    Object.assign(window, {
      emitWizzCompletion: () => {
        const metadata = { id: '', created: '', modified: '', removed: false }
        const caseSource = sources.get('/api/agentos/api/cases/22222222-2222-4222-8222-222222222222/events')
        const globalSource = sources.get('/api/agentos/api/cases/events/mine')
        const emit = (source: EventTarget | undefined, type: string, event: object) =>
          source?.dispatchEvent(new MessageEvent(type, { data: JSON.stringify(event) }))
        emit(caseSource, 'AgentRunningEvent', { type: 'AgentRunningEvent', id: 'run-1', caseId: '22222222-2222-4222-8222-222222222222', namespaceId: '11111111-1111-4111-8111-111111111111', timestamp: '', metadata, agentId: 'agent-1', agentName: 'Agent' })
        emit(caseSource, 'MessageEvent', { type: 'MessageEvent', id: 'message-1', caseId: '22222222-2222-4222-8222-222222222222', namespaceId: '11111111-1111-4111-8111-111111111111', timestamp: '', metadata, actor: { id: 'agent-1', role: 'AGENT', displayName: 'Agent' }, content: [] })
        const finish = { type: 'AgentFinishedEvent', id: 'finish-1', caseId: '22222222-2222-4222-8222-222222222222', namespaceId: '11111111-1111-4111-8111-111111111111', timestamp: '', metadata, agentId: 'agent-1', agentName: 'Agent' }
        emit(caseSource, 'AgentFinishedEvent', finish)
        emit(globalSource, 'AgentFinishedEvent', finish)
      },
    })
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() !== 'GET') return route.abort('blockedbyclient')
    const body = path === '/api/setup/status'
      ? { mode: 'unmanaged', setupEnabled: true, setupDone: true, steps: { agentos: { state: 'done' } } }
      : path.endsWith('/users/me')
        ? { id: 'user-1', externalId: 'user-1', email: 'e2e@example.test', firstname: 'Test', lastname: 'User' }
        : path.endsWith('/namespaces')
          ? [{ id: NAMESPACE_ID, name: 'Test namespace', role: 'ADMIN' }]
          : path.endsWith(`/cases/by-parentId/${NAMESPACE_ID}/mine`)
            ? [CASE]
            : path.endsWith(`/cases/${CASE_ID}`)
              ? CASE
              : path.endsWith('/directory')
              ? { path: '', entries: [], totalEntries: 0, hasMore: false, page: 0, capability: 'READ_WRITE' }
              : path.endsWith('/voice-messages/capability')
                ? { available: false }
                : path.endsWith('/usage-configuration') || path.endsWith('/git-workspaces-configuration')
                  ? { enabled: false }
                  : path.endsWith('/workspaces') || path.endsWith('/integration-types')
                    ? []
                    : undefined
    if (body === undefined) return route.fulfill({ status: 404, json: {} })
    return route.fulfill({ json: body })
  })
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page)
  await page.setViewportSize({ width: 390, height: 736 })
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
})

test('Wizz is keyboard-operable, exposes its pressed state, and remains reachable on mobile', async ({ page }) => {
  const wizz = page.getByRole('button', { name: 'Wizz response effect' })
  await expect(wizz).toBeInViewport()
  await expect(wizz).toHaveAttribute('aria-pressed', 'false')
  await wizz.focus()
  await page.keyboard.press('Enter')
  await expect(wizz).toHaveAttribute('aria-pressed', 'true')
  await page.keyboard.press('Space')
  await expect(wizz).toHaveAttribute('aria-pressed', 'false')
})

test('a live completed answer highlights the panel without moving it for reduced-motion users', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  const wizz = page.getByRole('button', { name: 'Wizz response effect' })
  await wizz.click()
  const chat = page.locator('.case-chat')
  const before = await chat.evaluate((element) => element.getBoundingClientRect().toJSON())
  await page.evaluate(() => (window as Window & { emitWizzCompletion: () => void }).emitWizzCompletion())
  await expect(chat).toHaveClass(/case-chat--wizzing/)
  const during = await chat.evaluate((element) => ({
    animationName: getComputedStyle(element).animationName,
    transform: getComputedStyle(element).transform,
    bounds: element.getBoundingClientRect().toJSON(),
  }))
  expect(during.animationName).toBe('case-chat-wizz-highlight')
  expect(during.transform).toBe('none')
  expect(during.bounds).toEqual(before)
})
