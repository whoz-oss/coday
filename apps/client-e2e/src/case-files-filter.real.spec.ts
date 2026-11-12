import { spawn } from 'node:child_process'
import * as fs from 'node:fs'
import * as net from 'node:net'
import * as os from 'node:os'
import * as path from 'node:path'
import { expect, groupMembers, ownedRoot, processChildren, requireListener, terminate, test } from './real-instance.fixture'

// These preflight checks exercise the same helpers as the isolated real stack on Linux and macOS.
test('TC-010 fixture validates temporary roots and loopback listener ownership', async () => {
  const temp = fs.realpathSync(os.tmpdir())
  const root = fs.mkdtempSync(path.join(temp, 'coday-tc010-'))
  const server = net.createServer()
  try {
    ownedRoot(root, temp)
    const link = path.join(root, 'link')
    fs.symlinkSync(root, link)
    expect(() => ownedRoot(link, temp)).toThrow()
    expect(() => ownedRoot(root, path.dirname(temp))).toThrow()
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject)
      server.listen(0, '127.0.0.1', resolve)
    })
    const address = server.address()
    if (!address || typeof address === 'string') throw new Error('No TCP address')
    expect(requireListener(address.port, [process.pid])).toBe(process.pid)
    expect(() => requireListener(address.port, [])).toThrow('Unowned or non-loopback listener')
  } finally {
    if (server.listening) await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
    ownedRoot(root, temp)
    fs.rmSync(root, { recursive: true, force: false })
    expect(fs.existsSync(root)).toBe(false)
  }
})

test('TC-010 fixture stops and verifies its detached process group', async () => {
  const child = spawn(process.execPath, ['-e', "require('node:child_process').spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)']); setInterval(() => {}, 1000)"], { detached: true, stdio: 'ignore' })
  try {
    if (!child.pid) throw new Error('Failed to start owned process group')
    const pid = child.pid
    await expect.poll(() => processChildren(pid).length, { timeout: 10_000 }).toBeGreaterThan(0)
    expect(groupMembers(pid)).toEqual(expect.arrayContaining([pid, ...processChildren(pid)]))
    await terminate(child)
    expect(groupMembers(pid)).toEqual([])
  } finally {
    await terminate(child)
  }
})

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
