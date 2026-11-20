import { expect, Page, test } from '@playwright/test'

const NAMESPACE_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'
const CASE = { id: CASE_ID, namespaceId: NAMESPACE_ID, title: 'Existing conversation', status: 'IDLE', role: 'ADMIN' }

async function mockBackend(page: Page): Promise<void> {
  await page.addInitScript(() => {
    class FakeEventSource extends EventTarget {
      readonly readyState = 1
      constructor(_url: string) {
        super()
        ;(window as Window & { wizzSource?: FakeEventSource }).wizzSource = this
      }
      close(): void {}
    }
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource })
  })
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const bodies: Record<string, unknown> = {
      '/api/setup/status': { mode: 'unmanaged', setupEnabled: true, setupDone: true, steps: { agentos: { state: 'done' } } },
    }
    const body =
      bodies[path] ??
      (path.endsWith('/users/me')
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
                      : undefined)
    if (body === undefined) await route.fulfill({ status: 404, json: {} })
    else await route.fulfill({ json: body })
  })
}

async function emit(page: Page, type: string, id: string, fields: object = {}): Promise<void> {
  await page.evaluate(
    ({ type, id, fields }) => {
      const source = (window as Window & { wizzSource: EventTarget }).wizzSource
      source.dispatchEvent(
        new MessageEvent(type, {
          data: JSON.stringify({ type, id, caseId: '22222222-2222-4222-8222-222222222222', namespaceId: '11111111-1111-4111-8111-111111111111', timestamp: '', metadata: { id, created: '', modified: '', removed: false }, ...fields }),
        })
      )
    },
    { type, id, fields }
  )
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page)
  await page.setViewportSize({ width: 1200, height: 736 })
  await page.goto(`/home?ns=${NAMESPACE_ID}&case=${CASE_ID}`)
})

test('Wizz is keyboard-accessible and does not change message-region geometry on completion', async ({ page }) => {
  const wizz = page.getByRole('button', { name: 'Wizz: highlight completed agent responses' })
  const region = page.locator('.case-chat__messages-region')
  await expect(wizz).toHaveAttribute('aria-pressed', 'false')
  await wizz.focus()
  await page.keyboard.press('Space')
  await expect(wizz).toHaveAttribute('aria-pressed', 'true')

  const before = await region.evaluate((element) => ({ box: element.getBoundingClientRect().toJSON(), scrollHeight: element.scrollHeight }))
  await emit(page, 'ThinkingEvent', 'thinking-1')
  await emit(page, 'TextChunkEvent', 'chunk-1', { chunk: 'Done' })
  await emit(page, 'MessageEvent', 'message-1', { actor: { id: 'agent-1', role: 'AGENT', displayName: 'Agent' }, content: [{ type: 'Text', content: 'Done' }] })
  await emit(page, 'AgentFinishedEvent', 'finish-1', { agentId: 'agent-1', agentName: 'Agent' })

  await expect(region).toHaveClass(/wizz-a/)
  const during = await region.evaluate((element) => ({ box: element.getBoundingClientRect().toJSON(), scrollHeight: element.scrollHeight }))
  expect(during.box.width).toBe(before.box.width)
  expect(during.box.height).toBe(before.box.height)
  expect(during.scrollHeight).toBeGreaterThanOrEqual(before.scrollHeight)
})

test('reduced motion replaces the shake with a stationary highlight', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.getByRole('button', { name: 'Wizz: highlight completed agent responses' }).click()
  await emit(page, 'ThinkingEvent', 'thinking-1')
  await emit(page, 'MessageEvent', 'message-1', { actor: { id: 'agent-1', role: 'AGENT' }, content: [{ type: 'Text', content: 'Done' }] })
  await emit(page, 'AgentFinishedEvent', 'finish-1', { agentId: 'agent-1', agentName: 'Agent' })
  await expect(page.locator('.case-chat__messages-region')).toHaveClass(/wizz-a/)
  expect(
    await page.locator('.case-chat__messages-region').evaluate((element) => getComputedStyle(element).animationName)
  ).toContain('highlight')
})
