import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
const agentos=await readFile(new URL('../lib/agentos.mjs',import.meta.url),'utf8'),executor=await readFile(new URL('../lib/factory-agent-step-executor.mjs',import.meta.url),'utf8')
assert.equal(agentos.includes('trustedSessionContext'),false)
assert.equal(executor.includes('sessionContext'),false)
assert.match(executor,/bindFactoryStepResult/)
assert.match(agentos,/x-factory-agentos-secret/)
