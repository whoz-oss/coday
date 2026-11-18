import { expect, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'
const CASE = { id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Wizz case', status: 'IDLE', role: 'ADMIN' }

async function mockBackend(page: import('@playwright/test').Page): Promise<void> {
  await page.addInitScript(() => {
    class FakeEventSource extends EventTarget {
      static current: FakeEventSource | null = null
      readonly readyState = 1
      constructor() {
        super()
        FakeEventSource.current = this
      }
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
    ;(window as unknown as { emitCaseEvent: (type: string, body: object) => void }).emitCaseEvent = (type, body) =>
      FakeEventSource.current?.dispatchEvent(new MessageEvent(type, { data: JSON.stringify({ type, ...body }) }))
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const bodies: Record<string, unknown> = {
      '/api/setup/status': { mode: 'unmanaged', setupEnabled: true, setupDone: true, steps: { agentos: { state: 'done' } } },
    }
    const body =
      bodies[path] ??
      (path.endsWith('/users/me') ? { id: 'user-1', email: 'e2e@example.test' } :
      path.endsWith('/namespaces') ? [{ id: NAMESPACE_ID, name: 'Test namespace', role: 'ADMIN' }] :
      path.endsWith(`/cases/by-parentId/${NAMESPACE_ID}/mine`) || path.endsWith(`/cases/${CASE_ID}`) ? CASE :
      path.endsWith('/directory') ? { path: '', entries: [], totalEntries: 0, hasMore: false, page: 0, capability: 'READ_WRITE' } :
      path.endsWith('/voice-messages/capability') ? { available: false } :
      path.endsWith('/usage-configuration') || path.endsWith('/git-workspaces-configuration') ? { enabled: false } :
      path.endsWith('/workspaces') || path.endsWith('/integration-types') ? [] : undefined)
    if (body === undefined) await route.fulfill({ status: 404, json: {} })
    else await route.fulfill({ json: body })
  })
}

test('Wizz toggles accessibly and only animates the messages region', async ({ page }) => {
  await mockBackend(page)
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
  const toggle = page.getByRole('button', { name: 'Wizz' })
  await expect(toggle).toHaveAttribute('aria-pressed', 'false')
  await toggle.press('Enter')
  await expect(toggle).toHaveAttribute('aria-pressed', 'true')

  const before = await page.locator('.case-chat__messages-region').evaluate((el) => el.getBoundingClientRect().toJSON())
  await page.evaluate(() => {
    ;(window as unknown as { emitCaseEvent: (type: string, body: object) => void }).emitCaseEvent('ThinkingEvent', { id: 'thinking', caseId: 'c', namespaceId: 'n' })
    ;(window as unknown as { emitCaseEvent: (type: string, body: object) => void }).emitCaseEvent('MessageEvent', { id: 'message', caseId: 'c', namespaceId: 'n', actor: { id: 'agent', role: 'AGENT' }, content: [{ type: 'Text', content: 'Done' }] })
    ;(window as unknown as { emitCaseEvent: (type: string, body: object) => void }).emitCaseEvent('AgentFinishedEvent', { id: 'finished', caseId: 'c', namespaceId: 'n', agentId: 'agent' })
  })
  await expect(page.locator('.case-chat__messages-region')).toHaveClass(/wizz-one|wizz-two/)
  const after = await page.locator('.case-chat__messages-region').evaluate((el) => el.getBoundingClientRect().toJSON())
  expect(after.width).toBe(before.width)
  expect(after.height).toBe(before.height)
})
