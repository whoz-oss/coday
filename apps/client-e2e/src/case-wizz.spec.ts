import { expect, Page, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'
const CASE = { id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Wizz conversation', status: 'IDLE', role: 'ADMIN' }

async function mockBackend(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const sources: EventTarget[] = []
    class FakeEventSource extends EventTarget {
      readonly readyState = 1
      constructor(..._args: unknown[]) {
        super()
        sources.push(this)
      }
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
    Object.assign(window, {
      emitCaseEvent: (type: string, event: object) => {
        for (const source of sources) source.dispatchEvent(new MessageEvent(type, { data: JSON.stringify(event) }))
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

async function emit(page: Page, type: string, id: string, fields: object = {}): Promise<void> {
  await page.evaluate(
    ({ type, event }) =>
      (window as unknown as { emitCaseEvent: (name: string, payload: object) => void }).emitCaseEvent(type, event),
    { type, event: { type, id, caseId: CASE_ID, namespaceId: NAMESPACE_ID, timestamp: '', metadata: {}, ...fields } }
  )
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page)
  await page.setViewportSize({ width: 1200, height: 736 })
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
  await expect(page.getByPlaceholder('Reply…')).toBeVisible()
})

test('Wizz is keyboard-accessible and does not change messages geometry', async ({ page }) => {
  const button = page.getByRole('button', { name: 'Wizz mode' })
  const region = page.locator('.case-chat__messages-region')
  await expect(button).toHaveAttribute('aria-pressed', 'false')
  await button.focus()
  await page.keyboard.press('Space')
  await expect(button).toHaveAttribute('aria-pressed', 'true')

  await emit(page, 'ThinkingEvent', 'thinking-1')
  await emit(page, 'MessageEvent', 'message-1', {
    actor: { id: 'agent-1', role: 'AGENT' },
    content: [{ type: 'Text', content: 'Completed response' }],
  })
  const before = await region.evaluate((element) => {
    const messages = element.querySelector<HTMLElement>('.case-chat__messages')!
    const box = element.getBoundingClientRect()
    return { top: box.top, width: box.width, height: box.height, scrollTop: messages.scrollTop, scrollHeight: messages.scrollHeight }
  })
  await emit(page, 'AgentFinishedEvent', 'finished-1', { agentId: 'agent-1', agentName: 'Agent' })
  await expect(region).toHaveClass(/case-chat__messages-region--wizzing/)

  const during = await region.evaluate((element) => {
    const messages = element.querySelector<HTMLElement>('.case-chat__messages')!
    const box = element.getBoundingClientRect()
    return { top: box.top, width: box.width, height: box.height, scrollTop: messages.scrollTop, scrollHeight: messages.scrollHeight }
  })
  expect(during).toEqual(before)
  await expect(region).not.toHaveClass(/case-chat__messages-region--wizzing/)
})

test('Wizz uses a stationary reduced-motion highlight', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  const button = page.getByRole('button', { name: 'Wizz mode' })
  await button.press('Enter')
  await emit(page, 'TextChunkEvent', 'chunk-1', { chunk: 'live' })
  await emit(page, 'MessageEvent', 'message-1', {
    actor: { id: 'agent-1', role: 'AGENT' },
    content: [{ type: 'Text', content: 'Completed response' }],
  })
  await emit(page, 'AgentFinishedEvent', 'finished-1', { agentId: 'agent-1', agentName: 'Agent' })

  const animation = await page.locator('.case-chat__messages-region').evaluate((element) => {
    const style = getComputedStyle(element)
    return { name: style.animationName, transform: style.transform }
  })
  expect(animation.name).toBe('case-chat-wizz-highlight')
  expect(animation.transform).toBe('none')
})
