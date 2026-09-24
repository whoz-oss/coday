import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
const httpClient=await readFile(new URL('../src/adapters/agentos/agentos-http-client.ts',import.meta.url),'utf8'),
  facade=await readFile(new URL('../lib/agentos.mjs',import.meta.url),'utf8'),
  executor=await readFile(new URL('../lib/factory-agent-step-executor.mjs',import.meta.url),'utf8')
assert.equal(httpClient.includes('trustedSessionContext'),false)
assert.equal(executor.includes('sessionContext'),false)
assert.match(executor,/bindFactoryStepResult/)
assert.match(httpClient,/x-factory-agentos-secret/)
assert.match(facade,/bindFactoryStepResult/)
