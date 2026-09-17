import { handleWorkflowDefinitionRequest } from '../dashboard/workflow-definition-routes.mjs'

let failed = 0
function expect(name, condition) { console.log(`${condition ? '✓' : '✗'} ${name}`); if (!condition) failed++ }
const definition = { schemaVersion: '1', workflowType: 'bmad-story', version: '1.0.0', title: 'BMAD', steps: [], definitionHash: 'abc' }
const registry = { list: async () => [definition], get: async (type, version) => type === 'bmad-story' && version === '1.0.0' ? definition : null }
async function request(method, path) { let response; const handled = await handleWorkflowDefinitionRequest({ method, path, registry, send: (status, body) => { response = { status, body } } }); return { handled, ...response } }
let response = await request('GET', '/api/factory/workflow-definitions')
expect('namespace-independent list API', response.handled && response.status === 200 && response.body.data.items[0].definitionHash === 'abc')
response = await request('GET', '/api/factory/workflow-definitions/bmad-story/1.0.0')
expect('detail API', response.status === 200 && response.body.data.version === '1.0.0')
response = await request('GET', '/api/factory/workflow-definitions/missing/1.0.0')
expect('missing definition', response.status === 404)
response = await request('POST', '/api/factory/workflow-definitions')
expect('mutation unavailable', response.status === 405)
response = await request('GET', '/api/unrelated')
expect('unrelated route ignored', response.handled === false)
process.exit(failed ? 1 : 0)
