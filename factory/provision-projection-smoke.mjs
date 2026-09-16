import process from 'node:process'

const namespaceId = process.env.FACTORY_NAMESPACE_ID ?? '0d4bd471-df37-43d8-a8f7-c989f95e71d7'
const baseUrl = process.env.AGENTOS_URL ?? 'http://localhost:8124'
const user = process.env.FACTORY_USER ?? 'benjamin.valdes'
const agentName = 'workflow-projection-smoke'

const instructions = `You are a specialized smoke agent for the generic Factory workflow projection.

Model a domain-neutral demo workflow named "Prepare a demo" with workflowId "projection-smoke-demo", workflowType "demo", and these stable step IDs, which must never be renamed between turns:
- define-goal
- prepare-demo (depends on define-goal)
- review-result (depends on prepare-demo)

After material progress, call FACTORY__publish_projection with the complete WorkflowProjection v1, including every step and dependency. Never print projection JSON as a substitute for calling the tool.

Use only these exact status values for the workflow and steps: pending, ready, running, waiting_human, blocked, completed, failed, cancelled. Use "running", never "in_progress", for work in progress. Send schemaVersion as the string "1".

On the first user turn, publish a useful running state without expectedRevision. On each later user turn, preserve the same workflow and step IDs, apply the requested progress, and republish the complete projection with expectedRevision equal to the revision returned by the previous successful publication. Briefly report the publication result only after the tool call succeeds.`

async function request(method, path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-External-User-Id': user },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const text = await response.text()
  if (!response.ok) throw new Error(`AgentOS ${method} ${path} -> HTTP ${response.status}\n${text}`)
  return text ? JSON.parse(text) : null
}

const agents = await request('GET', `/api/agent-configs/by-parentId/${namespaceId}`)
const existing = agents.find((agent) => agent.name.toLowerCase() === agentName)
const payload = {
  namespaceId,
  name: agentName,
  description: 'Minimal specialized agent for the Factory WorkflowProjection vertical smoke.',
  instructions,
  integrations: {
    FACTORY: ['publish_projection'],
    QUERY_USER: [],
    CASE_FILE_EXCHANGE: [],
    NAMESPACE_FILE_EXCHANGE: [],
  },
  advancedExecution: false,
  subAgents: [],
  enabled: true,
}
const saved = existing
  ? await request('PUT', `/api/agent-configs/${existing.id}`, payload)
  : await request('POST', '/api/agent-configs', payload)
const verified = await request('GET', `/api/agent-configs/${saved.id}`)
const expectedKeys = ['CASE_FILE_EXCHANGE', 'FACTORY', 'NAMESPACE_FILE_EXCHANGE', 'QUERY_USER']
const actualKeys = Object.keys(verified.integrations ?? {}).sort()
const problems = []
if (verified.enabled !== true) problems.push('agent is not enabled')
if (JSON.stringify(actualKeys) !== JSON.stringify(expectedKeys.sort())) problems.push(`unexpected integration keys: ${actualKeys.join(', ')}`)
if (JSON.stringify(verified.integrations?.FACTORY) !== JSON.stringify(['publish_projection'])) problems.push('FACTORY grant is not exactly [publish_projection]')
for (const key of ['QUERY_USER', 'CASE_FILE_EXCHANGE', 'NAMESPACE_FILE_EXCHANGE']) {
  if (!Array.isArray(verified.integrations?.[key]) || verified.integrations[key].length !== 0) problems.push(`${key} is not explicitly disabled`)
}
if ((verified.subAgents ?? []).length !== 0) problems.push('subAgents is not empty')
if (problems.length) throw new Error(`Provisioned agent failed verification: ${problems.join('; ')}`)
console.log(`Ready: ${verified.name} (${verified.id}) in namespace ${namespaceId}`)
console.log('Grant: FACTORY -> publish_projection; all optional interactive/file/delegation capabilities disabled.')
